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
        self.log_channel_id = None
        self.log_channel = None
        self.guild_id = None
        self.online_players = set()
        self.query_host = "127.0.0.1"
        self.query_port = 25565
        self.status_url = ""
        self.panel_message_id = None
        self.last_server_status = {}
        self.panel_task = None
        self.server_address = ""

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

        log_channel = os.getenv('MC_LOG_CHANNEL_ID', '').strip()
        if log_channel.isdigit():
            self.log_channel_id = int(log_channel)

        guild_id = os.getenv('MC_GUILD_ID', '').strip()
        if guild_id.isdigit():
            self.guild_id = int(guild_id)

        panel_message = os.getenv('MC_PANEL_MESSAGE_ID', '').strip()
        if panel_message.isdigit():
            self.panel_message_id = int(panel_message)

        await self.start_api_server()

        await self.tree.sync()
        print("Synced Slash Commands.")

        self.panel_task = asyncio.create_task(self.panel_loop())

    async def start_api_server(self):
        api_key = os.getenv('MC_AUTH_API_KEY', '')
        bind_host = os.getenv('MC_AUTH_BIND_HOST', '127.0.0.1')
        bind_port = int(os.getenv('MC_AUTH_BIND_PORT', '8080'))

        app = web.Application()
        app['pool'] = self.pool
        app['api_key'] = api_key
        app.router.add_get('/v1/registration/{minecraft_uuid}', self.handle_registration)
        app.router.add_post('/v1/mc-event', self.handle_mc_event)
        app.router.add_get('/v1/role/{minecraft_uuid}', self.handle_role_info)
        app.router.add_post('/v1/server-status', self.handle_server_status)
        app.router.add_get('/v1/web-status', self.handle_web_status)

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

    async def handle_mc_event(self, request: web.Request):
        api_key = request.app['api_key']
        provided_key = request.headers.get('X-API-Key', '')
        if api_key and provided_key != api_key:
            return web.json_response({'ok': False, 'error': 'unauthorized'}, status=401)

        try:
            payload = await request.json()
        except Exception:
            return web.json_response({'ok': False, 'error': 'invalid_json'}, status=400)

        minecraft_uuid = payload.get('uuid', '')
        minecraft_name = payload.get('name', '')
        event_type = payload.get('event', '')

        try:
            uuid.UUID(minecraft_uuid)
        except ValueError:
            return web.json_response({'ok': False, 'error': 'invalid_uuid'}, status=400)

        if event_type not in ('join', 'leave'):
            return web.json_response({'ok': False, 'error': 'invalid_event'}, status=400)

        if not self.log_channel_id:
            return web.json_response({'ok': False, 'error': 'log_channel_not_configured'}, status=400)

        async with request.app['pool'].acquire() as conn:
            result = await conn.fetchrow(
                'SELECT discord_id FROM users WHERE minecraft_uuid = $1',
                minecraft_uuid
            )

        if event_type == "join" and minecraft_name:
            self.online_players.add(minecraft_name)
        elif event_type == "leave" and minecraft_name:
            self.online_players.discard(minecraft_name)

        await self.update_panel()
        return web.json_response({'ok': True})

    async def handle_role_info(self, request: web.Request):
        api_key = request.app['api_key']
        provided_key = request.headers.get('X-API-Key', '')
        if api_key and provided_key != api_key:
            return web.json_response({'ok': False, 'error': 'unauthorized'}, status=401)

        if not self.guild_id:
            return web.json_response({'ok': False, 'error': 'guild_not_configured'}, status=400)

        minecraft_uuid = request.match_info.get('minecraft_uuid', '')
        try:
            uuid.UUID(minecraft_uuid)
        except ValueError:
            return web.json_response({'ok': False, 'error': 'invalid_uuid'}, status=400)

        async with request.app['pool'].acquire() as conn:
            result = await conn.fetchrow(
                'SELECT discord_id FROM users WHERE minecraft_uuid = $1',
                minecraft_uuid
            )

        if not result or not result['discord_id']:
            return web.json_response({'ok': False, 'error': 'not_linked'}, status=404)

        discord_id = int(result['discord_id'])
        guild = self.get_guild(self.guild_id)
        if guild is None:
            try:
                guild = await self.fetch_guild(self.guild_id)
            except discord.HTTPException:
                return web.json_response({'ok': False, 'error': 'guild_not_found'}, status=404)

        try:
            member = await guild.fetch_member(discord_id)
        except discord.HTTPException:
            return web.json_response({'ok': False, 'error': 'member_not_found'}, status=404)

        roles = [role for role in member.roles if not role.is_default()]
        if not roles:
            return web.json_response({'ok': True, 'role': '', 'color': 0})

        top_role = max(roles, key=lambda r: r.position)
        return web.json_response({'ok': True, 'role': top_role.name, 'color': top_role.color.value})

    async def handle_web_status(self, request: web.Request):
        status = await self.fetch_server_status()
        online_names = self.online_players
        day = self.last_server_status.get('day')
        time_of_day = self.last_server_status.get('time')

        payload = {
            'ok': True,
            'players': {
                'online': status.get('online', len(online_names)),
                'max': status.get('max', 0),
                'list': sorted(online_names),
            },
            'latency': status.get('ping'),
            'version': status.get('version'),
            'day': day,
            'time': time_of_day,
        }
        return web.json_response(payload, headers={'Access-Control-Allow-Origin': '*'})

    async def handle_server_status(self, request: web.Request):
        api_key = request.app['api_key']
        provided_key = request.headers.get('X-API-Key', '')
        if api_key and provided_key != api_key:
            return web.json_response({'ok': False, 'error': 'unauthorized'}, status=401)

        try:
            payload = await request.json()
        except Exception:
            return web.json_response({'ok': False, 'error': 'invalid_json'}, status=400)

        day = payload.get('day')
        time_of_day = payload.get('time')
        if not isinstance(day, int) or not isinstance(time_of_day, int):
            return web.json_response({'ok': False, 'error': 'invalid_payload'}, status=400)

        self.last_server_status = {'day': day, 'time': time_of_day}
        await self.update_panel()
        return web.json_response({'ok': True})

    async def panel_loop(self):
        while True:
            try:
                await self.update_panel()
            except Exception:
                pass
            await asyncio.sleep(30)

    async def update_panel(self):
        if not self.log_channel_id:
            return

        channel = self.log_channel
        if channel is None:
            channel = self.get_channel(self.log_channel_id)
        if channel is None:
            try:
                channel = await self.fetch_channel(self.log_channel_id)
            except discord.HTTPException:
                return
            self.log_channel = channel

        online_names = await self.fetch_online_players()
        if online_names is None or not online_names:
            online_names = self.online_players

        status = await self.fetch_server_status()

        embed = discord.Embed(
            title="Minecraft Server Panel",
            url="https://github.com/M4R5-PH0B05/MinecraftDCLink",
            description="This panel shows live server status, online players, and game time. Any issues, contact mars_phobos.",
            color=discord.Color.blurple(),
            timestamp=discord.utils.utcnow()
        )
        online_count = len(online_names) if online_names else status.get('online', 0)
        max_players = status.get('max', 0)
        ping = status.get('ping', None)

        if max_players:
            embed.add_field(name="Players", value=f"👥 {online_count}/{max_players}", inline=True)
        else:
            embed.add_field(name="Players", value=f"👥 {online_count}", inline=True)
        if ping is not None:
            embed.add_field(name="Ping", value=f"📶 {ping} ms", inline=True)
        if self.server_address:
            embed.add_field(name="Server IP", value=f"🔗 {self.server_address}", inline=True)

        day = self.last_server_status.get('day')
        time_of_day = self.last_server_status.get('time')
        if isinstance(day, int) and isinstance(time_of_day, int):
            hour = ((time_of_day + 6000) % 24000) / 1000.0
            hours = int(hour)
            minutes = int((hour - hours) * 60)
            embed.add_field(name="Game Time", value=f"🗓️ Day {day}, ⏱️ {hours:02d}:{minutes:02d}", inline=True)
        else:
            embed.add_field(name="Game Time", value="🗓️ Unknown", inline=True)

        if online_names:
            names_list = sorted(online_names)
            players_value = ", ".join([f"🟢 {name}" for name in names_list])
            if len(players_value) > 1000:
                players_value = players_value[:1000] + "..."
            embed.add_field(name="Online Players", value=players_value, inline=False)
        else:
            embed.add_field(name="Online Players", value="🔴 None", inline=False)

        embed.set_footer(text="MinecraftDCLink • View on GitHub")

        message = None
        if self.panel_message_id:
            try:
                message = await channel.fetch_message(self.panel_message_id)
            except discord.HTTPException:
                message = None

        if message is None:
            message = await channel.send(embed=embed)
            self.panel_message_id = message.id
            print(f"Panel message ID: {self.panel_message_id}")
        else:
            await message.edit(embed=embed)

    async def fetch_online_players(self):
        if self.status_url:
            url = self.status_url
        else:
            url = f"https://api.mcstatus.io/v2/status/java/{self.query_host}:{self.query_port}"

        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(url, timeout=5) as response:
                    if response.status != 200:
                        return None
                    data = await response.json()
        except Exception:
            return None

        players = data.get('players', {})
        raw_list = players.get('list', [])
        if isinstance(raw_list, list):
            names = []
            for entry in raw_list:
                if isinstance(entry, str):
                    names.append(entry)
                elif isinstance(entry, dict):
                    name = entry.get('name_raw') or entry.get('name_clean') or entry.get('name')
                    if name:
                        names.append(name)
            return set(names)
        return set()

    async def fetch_server_status(self):
        if self.status_url:
            url = self.status_url
        else:
            url = f"https://api.mcstatus.io/v2/status/java/{self.query_host}:{self.query_port}"

        try:
            async with aiohttp.ClientSession() as session:
                async with session.get(url, timeout=5) as response:
                    if response.status != 200:
                        return {}
                    data = await response.json()
        except Exception:
            return {}

        players = data.get('players', {})
        online = players.get('online', 0)
        max_players = players.get('max', 0)
        ping = data.get('latency')
        version = data.get('version', {})
        version_name = version.get('name_clean') or version.get('name')
        return {'online': online, 'max': max_players, 'ping': ping, 'version': version_name}

    async def close(self):
        if self.api_runner:
            await self.api_runner.cleanup()
        if self.panel_task:
            self.panel_task.cancel()
        await super().close()

# MAIN BOT
class RegistrationBot:
    MAX_USERS = 20  # Maximum number of users allowed to register

    def __init__(self):
        # INTENTS
        print("")
        intents = discord.Intents.default()
        intents.message_content = False

        self.client = MCRegistrationClient(intents=intents)
        self.client.query_host = os.getenv('MC_QUERY_HOST', '127.0.0.1')
        self.client.query_port = int(os.getenv('MC_QUERY_PORT', '25565'))
        self.client.status_url = os.getenv('MC_STATUS_URL', '').strip()
        self.client.server_address = os.getenv('MC_SERVER_ADDRESS', '').strip()
        self.setup_commands()

    def setup_commands(self):
        @self.client.tree.command(name='register', description='Register your Minecraft Account')
        @app_commands.describe(minecraft_name='Your Minecraft Account Name')
        async def link_minecraft(interaction: discord.Interaction, minecraft_name: str):
            try:
                if not interaction.response.is_done():
                    await interaction.response.defer(ephemeral=True)

                if minecraft_name in self.client.online_players:
                    online_players = self.client.online_players
                else:
                    online_players = await self.client.fetch_online_players()
                    if online_players is None:
                        await interaction.followup.send(
                            "Cannot check server status right now. Try again later.",
                            ephemeral=True
                        )
                        return

                if not online_players:
                    await interaction.followup.send(
                        "Server did not provide an online player list. Rejoin the server and try again.",
                        ephemeral=True
                    )
                    return

                if minecraft_name not in online_players:
                    await interaction.followup.send(
                        f"`{minecraft_name}` is not online. Join the server first, then try again.",
                        ephemeral=True
                    )
                    return

                minecraft_uuid = await self.resolve_uuid(minecraft_name)
                if not minecraft_uuid:
                    await interaction.followup.send(
                        "Could not find that Minecraft name. Double-check spelling.",
                        ephemeral=True
                    )
                    return
                parsed_uuid = uuid.UUID(minecraft_uuid)

                if not self.client.pool:
                    await interaction.followup.send("Error connecting to DB.", ephemeral=True)
                    return

                async with self.client.pool.acquire() as conn:
                    try:
                        # First, check the total number of registered users
                        result = await conn.fetchrow('SELECT COUNT(*) AS user_count FROM users')
                        current_user_count = result['user_count']

                        # Check if we've reached the maximum number of users
                        if current_user_count >= self.MAX_USERS:
                            await interaction.followup.send(
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
                            await interaction.followup.send(
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

                        embed = discord.Embed(
                            title="Registration successful",
                            description=f"Linked `{minecraft_name}`. You can now join the server.",
                            color=discord.Color.green()
                        )
                        embed.set_thumbnail(url=f"https://crafatar.com/avatars/{parsed_uuid}?size=64&overlay")
                        await interaction.followup.send(embed=embed, ephemeral=True)

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
                        await interaction.followup.send(
                            f"Registration failed: {str(db_error)}",
                            ephemeral=True
                        )

            except ValueError:
                await interaction.followup.send(
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

    async def fetch_online_players(self):
        return await self.client.fetch_online_players()

    def run(self):
        self.client.run(os.getenv('DISCORD_BOT_TOKEN'))


if __name__ == "__main__":
    bot = RegistrationBot()
    bot.run()
