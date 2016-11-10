package br.com.gamemods.queue;

import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.InetAddress;
import java.text.DateFormat;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LetMeInPlugin extends JavaPlugin implements Listener
{
    private Pattern pattern = Pattern.compile("\\{([a-z]+?)}");
    private YamlConfiguration messages;
    private final PriorityQueue<Entry> queue = new PriorityQueue<Entry>();
    private Queue<Player> kickQueue = new ArrayDeque<Player>();
    private Permission permission;
    private int max;
    private int def;
    private int allow;
    private boolean uuidMethod;
    private boolean welcomeOnlyWhenQueued;
    private boolean autoJoinSupport;
    private boolean serverListEnabled;
    private int timeout;
    private ScheduledFuture<?> cleanupTask;

    private ScheduledExecutorService preciseScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
    {
        private int count;
        @Override
        public Thread newThread(Runnable r)
        {
            Thread thread = new Thread(r, "LetMeIn-" + ++count);
            thread.setDaemon(true);
            return thread;
        }
    });

    @Override
    public void onEnable()
    {
        saveDefaultConfig();
        reloadConfig();
        RegisteredServiceProvider<Permission> registration = getServer().getServicesManager().getRegistration(Permission.class);
        permission = registration == null? null : registration.getProvider();
        if(permission == null)
        {
            getLogger().severe("No permission plugin is registered in Vault, "+getName()+" is being disabled...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable()
    {
        preciseScheduler.shutdown();
        cleanupTask.cancel(true);
        try
        {
            preciseScheduler.awaitTermination(30, TimeUnit.SECONDS);
        }
        catch(InterruptedException e)
        {
            getLogger().log(Level.SEVERE, "Failed to wait the precise scheduler terminate", e);
        }
    }

    @Override
    public void reloadConfig()
    {
        super.reloadConfig();
        loadMessages();
        max = Math.min(Math.max(1, getConfig().getInt("max-priority", 3)), Integer.MAX_VALUE-1);
        def = Math.min(Math.max(0, getConfig().getInt("default-priority", 0)), Integer.MAX_VALUE-1);
        allow = Math.min(Math.max(1, getConfig().getInt("allow-to-join-post", 5)), Integer.MAX_VALUE);
        uuidMethod = getConfig().getString("get-player-by", "uuid").equalsIgnoreCase("uuid");
        timeout = Math.max(1, getConfig().getInt("timeout", 30));
        welcomeOnlyWhenQueued = getConfig().getBoolean("welcome-only-when-queued", true);
        autoJoinSupport = getConfig().getBoolean("auto-join-support", true);
        serverListEnabled = getConfig().getBoolean("server-list", true);

        if(cleanupTask != null)
            cleanupTask.cancel(true);
        cleanupTask = preciseScheduler.scheduleAtFixedRate(new Runnable()
        {
            @Override
            public void run()
            {
                synchronized(queue)
                {
                    long cut = System.currentTimeMillis() - timeout*1000L;
                    Iterator<Entry> iterator = queue.iterator();
                    while(iterator.hasNext())
                        if(iterator.next().request.lastLogin < cut)
                            iterator.remove();
                }
            }
        }, timeout, timeout, TimeUnit.SECONDS);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args)
    {
        String cmd = command.getName();
        if("letmeinreload".equals(cmd))
        {
            reloadConfig();
            sender.sendMessage(message("cmd.letmeinreload.success", ChatColor.GREEN+"The LetMeIn messages and configurations have been reloaded"));
            return true;
        }
        else if("queueclear".equals(cmd))
        {
            synchronized(queue)
            {
                queue.clear();
            }
            sender.sendMessage(message("cmd.queue.clear.success", ChatColor.GREEN+"The join queue have been cleared"));
            return true;
        }
        else if("queuepriority".equals(cmd))
        {
            if(args.length != 2)
                return false;

            int priority;
            try
            {
                priority = Integer.parseInt(args[1]);
            }
            catch(NumberFormatException ignored)
            {
                return false;
            }

            String name = args[0];
            Entry entry = findRequest(name);

            if(entry == null || entry.request == null)
            {
                sender.sendMessage(message("cmd.queue.priority.not-queued", ChatColor.RED+"{0} is not queued", name));
                return true;
            }

            Request request = entry.request;
            int before = request.priority;
            int pos = request.pos;
            request.priority = priority;
            updatePosition(entry);
            sender.sendMessage(message("cmd.queue.priority.success",
                    ChatColor.GREEN+"{0}'s priority was changed from {1} to {2} and the position was change from {3} to {4}",
                    name, before, priority, pos, request.pos
            ));
            return true;
        }
        else if("queueview".equalsIgnoreCase(cmd))
        {
            if(args.length != 1)
                return false;

            String name = args[0];
            Entry entry = findRequest(name);

            if(entry == null || entry.request == null)
            {
                @SuppressWarnings("deprecation")
                OfflinePlayer player = Bukkit.getOfflinePlayer(name);
                sender.sendMessage(message("cmd.queue.view.not-queued",
                        "{0} "+ChatColor.RED+"is not queued"+ChatColor.RESET+" but would receive priority {1}",
                        player.getName(), findPriorityPermission(player)
                ));
                return true;
            }

            Request request = entry.request;
            Map<String, Object> map = paramMap(request);
            map.put("last", DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(request.lastLogin)));

            sender.sendMessage(message("cmd.queue.view.success",
                    "{name} {pos}/{slots} Priority:{priority} Good:{good} Bad:{bad} Kicks:{kicks} Last:{last} Login:{login} UUID:{uuid}",
                    map
            ));
            return true;
        }
        else if("queue".equalsIgnoreCase(cmd))
        {
            StringBuilder sb = new StringBuilder();
            int pos = 1;
            String separator = message("cmd.queue.list.separator", ", ");
            String pattern = message("cmd.queue.list.pattern", "{prefix}{pos}:{name} ({priority}){suffix}");
            String headPrefix = message("cmd.queue.list.head.prefix", ChatColor.GREEN.toString());
            String headSuffix = message("cmd.queue.list.head.suffix", "");
            String middlePrefix = message("cmd.queue.list.middle.prefix", ChatColor.GOLD.toString());
            String middleSuffix = message("cmd.queue.list.middle.suffix", "");
            String underPrefix = message("cmd.queue.list.low-priority.prefix", ChatColor.RED.toString());
            String underSuffix = message("cmd.queue.list.low-priority.suffix", "");

            synchronized(queue)
            {
                Map<String, Object> map = new HashMap<String, Object>();
                for(Entry entry : queue)
                {
                    paramMap(entry.request, map);
                    if(pos <= allow)
                    {
                        map.put("prefix", headPrefix);
                        map.put("suffix", headSuffix);
                    }
                    else if(entry.request.priority < def)
                    {
                        map.put("prefix", underPrefix);
                        map.put("suffix", underSuffix);
                    }
                    else
                    {
                        map.put("prefix", middlePrefix);
                        map.put("suffix", middleSuffix);
                    }

                    sb.append(replaceTokens(pattern, map, false));
                    sb.append(separator);
                    pos++;
                }
            }

            if(pos == 1)
            {
                sender.sendMessage(message("cmd.queue.list.empty", ChatColor.GREEN+"Nobody is queued"));
                return true;
            }

            sb.setLength(sb.length()-separator.length());
            String msg = sb.toString();
            if(msg.contains("\n"))
            {
                sender.sendMessage(msg.split("\n"));
                return true;
            }

            sender.sendMessage(sb.toString());
            return true;
        }

        return false;
    }

    private Entry findRequest(String playerName)
    {
        synchronized(queue)
        {
            for(Entry entry : queue)
            {
                if(entry.request.player.getName().equalsIgnoreCase(playerName) || entry.request.login.equalsIgnoreCase(playerName))
                {
                    return entry;
                }
            }
        }

        return null;
    }

    private Entry updatePosition(Entry entry)
    {
        synchronized(queue)
        {
            Iterator<Entry> iter = queue.iterator();
            int pos;
            int scan = 1;
            while(iter.hasNext())
            {
                Entry next = iter.next();
                if(next.equals(entry))
                {
                    pos = scan;
                    next.request.pos = pos;
                    return next;
                }

                scan++;
            }

            return entry;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onPing(ServerListPingEvent event)
    {
        if(!serverListEnabled && !autoJoinSupport)
            return;

        long time = System.currentTimeMillis();
        int numPlayers = event.getNumPlayers();
        synchronized(queue)
        {
            for(Entry entry : queue)
            {
                if(entry.request.address.equals(event.getAddress()))
                {
                    if(autoJoinSupport)
                    {
                        if(entry.request.pos <= allow || (time - entry.request.lastLogin) / 2 > timeout)
                            event.setMaxPlayers(numPlayers + 1);
                        else if(numPlayers < event.getMaxPlayers())
                            event.setMaxPlayers(numPlayers);
                    }

                    if(serverListEnabled)
                        event.setMotd(message("server-list", entry.request));
                    return;
                }
            }
        }

        if(autoJoinSupport)
            event.setMaxPlayers(numPlayers +1);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    private void onPreLogin(AsyncPlayerPreLoginEvent event)
    {
        final Entry entry = new Entry(event.getUniqueId());
        final Server server = getServer();
        int pos;
        synchronized(queue)
        {
            Entry queued = queue.contains(entry)? updatePosition(entry) : null;
            if(queued != null)
            {
                entry.request = queued.request;
                pos = queued.request.pos;

                // Check if the player has received a better priority while queued,
                // for example, purchased a donor rank to join faster
                int updated = findPriorityPermission(entry.request.player);
                if(updated > entry.request.priority)
                {
                    entry.request.priority = updated;
                    queue.remove(queued);
                    queue.add(entry);
                    updatePosition(entry);
                    entry.request.goodPriority += pos - entry.request.pos;
                }
            }
            else
            {
                entry.request = new Request(entry.playerId, event.getName(), event.getAddress());
                if(entry.request.player.isBanned() || server.hasWhitelist() && !entry.request.player.isWhitelisted())
                    return;

                int size = queue.size();
                queue.add(entry);
                updatePosition(entry);
                pos = entry.request.pos;
                if((entry.request.goodPriority = size - pos + 1) > 0)
                {
                    for(Entry other : queue)
                    {
                        if(other == entry)
                            break;

                        other.request.badPriority++;
                    }
                }
            }
        }

        if(pos <= 0)
            return;

        entry.request.lastLogin = System.currentTimeMillis();

        int max = server.getMaxPlayers();
        int size = server.getOnlinePlayers().size();
        if(pos <= allow && max - size > 0)
            return;

        if(entry.request.kick && !kickQueue.isEmpty())
        {
            try
            {
                size = server.getScheduler().callSyncMethod(this, new Callable<Integer>()
                {
                    @Override
                    public Integer call() throws Exception
                    {
                        Player kick;
                        int newSize;
                        int newMax = server.getMaxPlayers();
                        while(newMax - (newSize=server.getOnlinePlayers().size()) <= 0 && (kick = kickQueue.poll()) != null)
                        {
                            kick.kickPlayer(message("kicked", kick));
                            entry.request.kicks++;
                        }

                        return newSize;
                    }
                }).get(5, TimeUnit.SECONDS);

                if(max - size > 0)
                    return;
            }
            catch(Exception e)
            {
                getLogger().log(Level.WARNING, "Failed to kick a player", e);
            }
        }

        event.setLoginResult(AsyncPlayerPreLoginEvent.Result.KICK_FULL);
        event.setKickMessage(message("queued", entry.request));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void onPreLoginMonitor(AsyncPlayerPreLoginEvent event)
    {
        switch(event.getLoginResult())
        {
            case KICK_BANNED:
            case KICK_WHITELIST:
                queue.remove(new Entry(event.getUniqueId()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void onJoin(PlayerJoinEvent event)
    {
        Player player = event.getPlayer();
        if(player.isOnline())
        {
            Entry entry = null;
            UUID uniqueId = player.getUniqueId();
            synchronized(queue)
            {
                Iterator<Entry> iterator = queue.iterator();
                while(iterator.hasNext())
                {
                    Entry search = iterator.next();
                    if(uuidMethod)
                    {
                        if(search.request.playerId.equals(uniqueId))
                        {
                            entry = search;
                            iterator.remove();
                            break;
                        }
                    }
                }
            }

            if(!permission.playerHas(player, "letmein.protected"))
                kickQueue.add(event.getPlayer());

            if(entry != null && entry.request.shouldWelcome())
                player.sendMessage(message("welcome", entry.request));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    private void onQuit(PlayerQuitEvent event)
    {
        kickQueue.remove(event.getPlayer());
    }

    private int findPriorityPermission(OfflinePlayer player)
    {
        Permission permission = this.permission;
        if(permission.playerHas(null, player, "letmein.staff"))
            return Integer.MIN_VALUE;

        int max = this.max;
        for(int i = 1; i <= max; i++)
        {
            if(permission.playerHas(null, player, "letmein.priority."+i))
                return i;
        }

        return def;
    }

    private String findMessage(String key, int priority)
    {
        ConfigurationSection sec = messages.getConfigurationSection("custom");
        if(sec != null)
        {
            for(int i = priority; i > 0; i++)
            {
                String msg = sec.getString(key, "");
                if(!msg.isEmpty())
                    return msg;
            }
        }

        return messages.getString(key, key);
    }

    private String message(String key, String fallback, Map<String, Object> paramMap)
    {
        String msg = messages.getString(key, fallback);
        return replaceTokens(msg, paramMap);
    }

    private String message(String key, String fallback)
    {
        String msg = messages.getString(key, fallback);
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    private String message(String key, String fallback, Object... params)
    {
        for(int i = 0; i < params.length; i++)
        {
            if(params[i] instanceof String)
                params[i] = ChatColor.stripColor((String) params[i]);
        }

        String msg = messages.getString(key, fallback);
        return MessageFormat.format(ChatColor.translateAlternateColorCodes('&', msg), params);
    }

    private String message(String key, Player player)
    {
        Map<String, Object> rep = new HashMap<String, Object>();
        rep.put("name", player.getName());
        rep.put("uuid", player.getUniqueId());
        int priority = findPriorityPermission(player);
        return replaceTokens(findMessage(key, priority), rep);
    }

    private Map<String, Object> paramMap(Request request)
    {
        Map<String, Object> rep = new HashMap<String, Object>();
        paramMap(request, rep);
        return rep;
    }

    private void paramMap(Request request, Map<String, Object> rep)
    {
        rep.put("name", request.player.getName());
        rep.put("login", request.login);
        rep.put("uuid", request.playerId);
        rep.put("priority", request.priority);
        rep.put("ip", request.address);
        rep.put("pos", request.pos);
        rep.put("good", request.goodPriority);
        rep.put("bad", request.badPriority);
        rep.put("timeout", timeout);
        rep.put("kicks", request.kicks);
        rep.put("slots", getServer().getMaxPlayers());
    }

    private String message(String key, Request request)
    {
        return replaceTokens(findMessage(key, request.priority), paramMap(request));
    }

    private String replaceTokens(String text, Map<String, Object> replacements)
    {
        return replaceTokens(text, replacements, true);
    }

    private String replaceTokens(String text, Map<String, Object> replacements, boolean stripColors)
    {
        Matcher matcher = pattern.matcher(text);

        StringBuilder builder = new StringBuilder();
        int i = 0;
        while (matcher.find())
        {
            Object replacement = replacements.get(matcher.group(1));
            if(stripColors)
                replacement = ChatColor.stripColor(String.valueOf(replacement));

            builder.append(ChatColor.translateAlternateColorCodes('&', text.substring(i, matcher.start())));
            if (replacement == null)
                builder.append(matcher.group(0));
            else
                builder.append(replacement);
            i = matcher.end();
        }
        builder.append(ChatColor.translateAlternateColorCodes('&', text.substring(i, text.length())));
        return builder.toString();
    }

    private void loadMessages()
    {
        String language = getConfig().getString("language", "en");
        String source = "messages_"+language+".yml";
        InputStream in = getResource(source);
        if(in == null)
        {
            source = "messages_en.yml";
            in = getResource(source);
            if(in == null)
            {
                getLogger().severe("Failed to load messages_en.yml");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
        }

        File file = new File(getDataFolder(), "messages_" + language + ".yml");
        try
        {
            if(!file.isFile())
            {
                OutputStream out = null;
                try
                {
                    out = new FileOutputStream(file);
                    byte[] buf = new byte[1024];
                    int len;
                    while((len = in.read(buf)) > 0)
                    {
                        out.write(buf, 0, len);
                    }
                }
                catch(IOException e)
                {
                    getLogger().log(Level.SEVERE, "Failed to save the default messages to "+file, e);
                }
                finally
                {
                    if(out != null)
                    {
                        try
                        {
                            out.close();
                        }
                        catch(IOException e)
                        {
                            getLogger().log(Level.WARNING, "Failed to close the file "+file, e);
                        }
                    }
                }
            }
        }
        finally
        {
            try
            {
                in.close();
            }
            catch(IOException e)
            {
                getLogger().log(Level.WARNING, "Failed to close the "+source+" input stream", e);
            }
        }

        Reader reader = getTextResource(source);
        if(reader == null)
        {
            getLogger().severe("Failed to load internal "+source+" for reading");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        YamlConfiguration defaults;
        try
        {
            defaults = YamlConfiguration.loadConfiguration(reader);
        }
        finally
        {
            try
            {
                in.close();
            }
            catch(IOException e)
            {
                getLogger().log(Level.WARNING, "Failed to close the "+source+" reader", e);
            }
        }

        if(file.isFile())
        {
            messages = YamlConfiguration.loadConfiguration(file);
            messages.addDefaults(defaults);
        }
        else
        {
            messages = new YamlConfiguration();
            messages.addDefaults(defaults);
        }
    }

    private final class Entry implements Comparable<Entry>
    {
        private UUID playerId;
        private Request request;

        private Entry(UUID playerId)
        {
            this.playerId = playerId;
        }

        @Override
        public int compareTo(Entry o)
        {
            if(request == null)
                return 1;

            if(o.request == null)
                return -1;

            return request.compareTo(o.request);
        }

        @Override
        public boolean equals(Object o)
        {
            if(this == o) return true;
            if(o == null || getClass() != o.getClass()) return false;

            Entry entry = (Entry) o;

            return playerId.equals(entry.playerId);

        }

        @Override
        public int hashCode()
        {
            return playerId.hashCode();
        }
    }

    private final class Request implements Comparable<Request>
    {
        private UUID playerId;
        private String login;
        private InetAddress address;
        private OfflinePlayer player;
        private int priority;
        private int pos;
        private int goodPriority;
        private int badPriority;
        private boolean kick;
        private int kicks;
        private long lastLogin = System.currentTimeMillis();

        @SuppressWarnings("deprecation")
        private Request(UUID playerId, String login, InetAddress address)
        {
            this.login = login;
            this.playerId = playerId;
            this.address = address;
            this.player = uuidMethod? Bukkit.getOfflinePlayer(playerId) : Bukkit.getOfflinePlayer(login);
            this.priority = findPriorityPermission(player);
            this.kick = permission.playerHas(null, player, "letmein.kick");
        }

        @Override
        public int compareTo(Request o)
        {
            return o.priority - priority;
        }

        @Override
        public boolean equals(Object o)
        {
            if(this == o) return true;
            if(o == null || getClass() != o.getClass()) return false;

            Request request = (Request) o;

            return playerId.equals(request.playerId);

        }

        @Override
        public int hashCode()
        {
            return playerId.hashCode();
        }

        private boolean shouldWelcome()
        {
            return !welcomeOnlyWhenQueued || badPriority != 0 || goodPriority != 0 || kicks != 0;
        }
    }
}
