package br.com.gamemods.queue;

import net.milkbowl.vault.permission.Permission;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.InetAddress;
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
    private int timeout;
    private ScheduledFuture<?> cleanupTask;

    private ScheduledExecutorService preciseScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory()
    {
        @Override
        public Thread newThread(Runnable r)
        {
            FutureTask<Thread> thread = new FutureTask<Thread>(new Callable<Thread>()
            {
                @Override
                public Thread call() throws Exception
                {
                    return Thread.currentThread();
                }
            });
            getServer().getScheduler().runTaskAsynchronously(LetMeInPlugin.this, thread);
            try
            {
                return thread.get(30, TimeUnit.SECONDS);
            }
            catch(Exception e)
            {
                throw new RuntimeException(e);
            }
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
            synchronized(queue)
            {
                queue.remove(new Entry(player.getUniqueId()));
            }

            if(!permission.playerHas(null, "letmein.protected"))
                kickQueue.add(event.getPlayer());
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

    private String message(String key, Player player)
    {
        Map<String, Object> rep = new HashMap<String, Object>();
        rep.put("name", player.getName());
        rep.put("uuid", player.getUniqueId());
        int priority = findPriorityPermission(player);
        return replaceTokens(findMessage(key, priority), rep);
    }

    private String message(String key, Request request)
    {
        Map<String, Object> rep = new HashMap<String, Object>();
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
        return replaceTokens(findMessage(key, request.priority), rep);
    }

    private String replaceTokens(String text, Map<String, Object> replacements)
    {
        Matcher matcher = pattern.matcher(text);

        StringBuilder builder = new StringBuilder();
        int i = 0;
        while (matcher.find())
        {
            Object replacement = replacements.get(matcher.group(1));
            builder.append(ChatColor.translateAlternateColorCodes('&', text.substring(i, matcher.start())));
            if (replacement == null)
                builder.append(matcher.group(0));
            else
                builder.append(ChatColor.stripColor(String.valueOf(replacement)));
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
    }
}
