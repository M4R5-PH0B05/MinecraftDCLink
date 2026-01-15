import os
import uuid
import asyncio
import asyncpg
import discord
from discord import app_commands
from dotenv import load_dotenv
import aiohttp
from aiohttp import web

# ENV VARIABLES
load_dotenv()


# MAIN CLASS
class MCRegistrationClient(discord.Client):
    def __init__(self, *, intents: discord.Intents):
        super().__init__(intents=intents)
        self.pool = None
        self.tree = app_commands.CommandTree(self)
        self.api_runner = None

    # CONNECT TO DB
    async def setup_hook(self):
        self.pool = await asyncpg.create_pool(
            host=os.getenv('DB_HOST'),
            port=int(os.getenv('DB_PORT')),
            database=os.getenv('DB_NAME'),
            user=os.getenv('DB_USER'),
            password=os.getenv('DB_PASSWORD'),
        )

        async with self.pool.acquire() as conn:
            await conn.execute('''
                CREATE TABLE IF NOT EXISTS users (
                    minecraft_uuid VARCHAR(36) PRIMARY KEY,
                    discord_id BIGINT UNIQUE,
                    current_username VARCHAR(255) NOT NULL,
                    registration_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            ''')

        await self.start_api_server()

        await self.tree.sync()
        print("Synced Slash Commands.")

    async def start_api_server(self):
        api_key = os.getenv('MC_AUTH_API_KEY', '')
        bind_host = os.getenv('MC_AUTH_BIND_HOST', '127.0.0.1')
        bind_port = int(os.getenv('MC_AUTH_BIND_PORT', '8080'))

        app = web.Application()
        app['pool'] = self.pool
        app['api_key'] = api_key
        app.router.add_get('/v1/registration/{minecraft_uuid}', self.handle_registration)

        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, bind_host, bind_port)
        await site.start()
        self.api_runner = runner
        print(f"API server running on {bind_host}:{bind_port}")

    async def handle_registration(self, request: web.Request):
        api_key = request.app['api_key']
        provided_key = request.headers.get('X-API-Key', '')
        if api_key and provided_key != api_key:
            return web.json_response({'registered': False, 'error': 'unauthorized'}, status=401)

        minecraft_uuid = request.match_info.get('minecraft_uuid', '')
        try:
            uuid.UUID(minecraft_uuid)
        except ValueError:
            return web.json_response({'registered': False, 'error': 'invalid_uuid'}, status=400)

        async with request.app['pool'].acquire() as conn:
            result = await conn.fetchrow(
                'SELECT discord_id FROM users WHERE minecraft_uuid = $1',
                minecraft_uuid
            )

        if result and result['discord_id']:
            return web.json_response({'registered': True, 'discord_id': int(result['discord_id'])})
        return web.json_response({'registered': False})

    async def close(self):
        if self.api_runner:
            await self.api_runner.cleanup()
        await super().close()

# MAIN BOT
class RegistrationBot:
    MAX_USERS = 20  # Maximum number of users allowed to register

    def __init__(self):
        # INTENTS
        print("")
        intents = discord.Intents.default()
        intents.message_content = True

        self.client = MCRegistrationClient(intents=intents)
        self.setup_commands()

    def setup_commands(self):
        @self.client.tree.command(name='register', description='Register your Minecraft Account')
        @app_commands.describe(minecraft_name='Your Minecraft Account Name')
        async def link_minecraft(interaction: discord.Interaction, minecraft_name: str):
            try:
                minecraft_uuid = await self.resolve_uuid(minecraft_name)
                if not minecraft_uuid:
                    await interaction.response.send_message(
                        "Could not find that Minecraft name. Double-check spelling.",
                        ephemeral=True
                    )
                    return
                parsed_uuid = uuid.UUID(minecraft_uuid)

                if not self.client.pool:
                    await interaction.response.send_message("Error connecting to DB.", ephemeral=True)
                    return

                async with self.client.pool.acquire() as conn:
                    try:
                        # First, check the total number of registered users
                        result = await conn.fetchrow('SELECT COUNT(*) AS user_count FROM users')
                        current_user_count = result['user_count']

                        # Check if we've reached the maximum number of users
                        if current_user_count >= self.MAX_USERS:
                            await interaction.response.send_message(
                                f"Registration is full. Maximum of {self.MAX_USERS} users have already been registered.",
                                ephemeral=True
                            )
                            return

                        # Check if this user is already registered
                        existing_user = await conn.fetchrow(
                            'SELECT 1 FROM users WHERE discord_id = $1 OR minecraft_uuid = $2',
                            interaction.user.id, str(parsed_uuid)
                        )

                        if existing_user:
                            await interaction.response.send_message(
                                "Either your Discord account or this Minecraft account are already registered.",
                                ephemeral=True
                            )
                            return

                        # Proceed with registration
                        await conn.execute('''
                            INSERT INTO users (minecraft_uuid, discord_id, current_username)
                            VALUES ($1, $2, $3)
                            ON CONFLICT (minecraft_uuid)
                            DO UPDATE SET
                                discord_id = EXCLUDED.discord_id,
                                current_username = EXCLUDED.current_username
                        ''', str(parsed_uuid), interaction.user.id, minecraft_name)

                        await interaction.response.send_message(
                            f"Successfully linked `{minecraft_name}`! You can now join the server.",
                            ephemeral=True
                        )

                        if interaction.guild and isinstance(interaction.user, discord.Member):
                            try:
                                await interaction.user.edit(nick=minecraft_name)
                            except discord.Forbidden:
                                await interaction.followup.send(
                                    "Linked, but I don't have permission to change your nickname.",
                                    ephemeral=True
                                )
                            except discord.HTTPException:
                                await interaction.followup.send(
                                    "Linked, but I couldn't change your nickname.",
                                    ephemeral=True
                                )

                    except Exception as db_error:
                        await interaction.response.send_message(
                            f"Registration failed: {str(db_error)}",
                            ephemeral=True
                        )

            except ValueError:
                await interaction.response.send_message(
                    "Invalid UUID format. Please provide a valid minecraft UUID.",
                    ephemeral=True
                )

        @self.client.tree.command(name="checklink", description="Check your Minecraft account link")
        async def check_link(interaction: discord.Interaction):
            if not self.client.pool:
                await interaction.response.send_message("Database connection error. Please contact an admin.",
                                                        ephemeral=True)
                return

            async with self.client.pool.acquire() as conn:
                result = await conn.fetchrow(
                    'SELECT minecraft_uuid, current_username FROM users WHERE discord_id = $1',
                    interaction.user.id
                )

                if result:
                    await interaction.response.send_message(
                        f"Your Discord is linked to the Minecraft account with the UUID: `{result['minecraft_uuid']}`",
                        ephemeral=True
                    )
                else:
                    await interaction.response.send_message(
                        "No Minecraft account is currently linked to your Discord.",
                        ephemeral=True
                    )

    async def resolve_uuid(self, minecraft_name: str):
        url = f"https://api.mojang.com/users/profiles/minecraft/{minecraft_name}"
        async with aiohttp.ClientSession() as session:
            async with session.get(url) as response:
                if response.status != 200:
                    return None
                data = await response.json()
                raw_uuid = data.get('id')
                if not raw_uuid or len(raw_uuid) != 32:
                    return None
                return str(uuid.UUID(raw_uuid))

    def run(self):
        self.client.run(os.getenv('DISCORD_BOT_TOKEN'))


if __name__ == "__main__":
    bot = RegistrationBot()
    bot.run()
