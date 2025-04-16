package com.deathlx;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent.Result;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class deathlx extends JavaPlugin implements Listener {

    private boolean isActive = false;
    private File bannedPlayersFile;
    private FileConfiguration bannedPlayersConfig;
    private final Map<UUID, Map<String, String>> playerBanCache = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> ipToBannedAccounts = new ConcurrentHashMap<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
    private String customBanMessage;
    private boolean banByIP = true;
    private boolean banByUsername = true;
    private int kickDelayTicks = 20;

    @Override
    public void onEnable() {
        try {
            // Salvar configuração padrão e criar pasta de dados
            saveDefaultConfig();
            createDataFolder();

            // Registrar eventos com prioridade alta
            getServer().getPluginManager().registerEvents(this, this);

            // Registrar comandos
            registerCommands();

            // Inicializar configuração
            loadConfiguration();

            // Carregar cache de jogadores banidos
            loadBannedPlayersCache();

            getLogger().info("Plugin DeathLX foi ativado com sucesso!");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Erro ao habilitar o plugin DeathLX: " + e.getMessage(), e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void createDataFolder() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Não foi possível criar o diretório do plugin!");
        }

        bannedPlayersFile = new File(getDataFolder(), "banned-players.yml");
        if (!bannedPlayersFile.exists()) {
            try {
                if (!bannedPlayersFile.createNewFile()) {
                    getLogger().warning("Não foi possível criar o arquivo banned-players.yml!");
                }
            } catch (IOException e) {
                getLogger().log(Level.SEVERE, "Erro ao criar arquivo de jogadores banidos", e);
            }
        }
    }

    private void registerCommands() {
        PluginCommand command = getCommand("deathlx");
        if (command != null) {
            DeathLXCommandExecutor executor = new DeathLXCommandExecutor();
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        } else {
            getLogger().severe("Não foi possível encontrar o comando 'deathlx' no plugin.yml. Verifique seu arquivo de configuração.");
        }
    }

    private void loadConfiguration() {
        // Carregar arquivo de jogadores banidos
        bannedPlayersConfig = YamlConfiguration.loadConfiguration(bannedPlayersFile);

        // Criar seção players se não existir
        if (!bannedPlayersConfig.contains("players")) {
            bannedPlayersConfig.createSection("players");
            saveData();
        }

        // Carregar configurações do config.yml
        FileConfiguration config = getConfig();

        // Definir valores padrão se não existirem
        if (!config.contains("active")) {
            config.set("active", false);
        }

        if (!config.contains("ban-methods.ip")) {
            config.set("ban-methods.ip", true);
        }

        if (!config.contains("ban-methods.username")) {
            config.set("ban-methods.username", true);
        }

        if (!config.contains("kick-delay-ticks")) {
            config.set("kick-delay-ticks", 20);
        }

        if (!config.contains("custom-ban-message")) {
            config.set("custom-ban-message",
                    ChatColor.AQUA + "-----------------------------------\n" +
                            ChatColor.GREEN + "   EVENTO HARDCORE MINEZINHO\n" +
                            ChatColor.AQUA + "-----------------------------------\n" +
                            "\n" +
                            ChatColor.GREEN + "PARABÉNS %player%!\n" +
                            ChatColor.GREEN + "Você mostrou garra e chegou longe em nosso evento!\n" +
                            "\n" +
                            ChatColor.YELLOW + "» Data da sua morte: %death_time%\n" +
                            ChatColor.YELLOW + "» Início da jornada: %first_join%\n" +
                            "\n" +
                            ChatColor.LIGHT_PURPLE + "Sua aventura chegou ao fim, mas sua coragem será lembrada!\n" +
                            ChatColor.LIGHT_PURPLE + "No hardcore, cada escolha importa, e não há segunda chance!\n" +
                            "\n" +
                            ChatColor.GREEN + "Obrigado por participar! Nos vemos no próximo evento!\n" +
                            ChatColor.AQUA + "----------------------------------------");
        }

        // Salvar configurações padrão
        saveConfig();

        // Carregar os valores
        isActive = config.getBoolean("active");
        banByIP = config.getBoolean("ban-methods.ip");
        banByUsername = config.getBoolean("ban-methods.username");
        kickDelayTicks = config.getInt("kick-delay-ticks");
        customBanMessage = config.getString("custom-ban-message");

        getLogger().info("Plugin DeathLX está " + (isActive ? "ativado" : "desativado"));
        getLogger().info("Método de banimento por IP: " + (banByIP ? "ATIVADO" : "DESATIVADO"));
        getLogger().info("Método de banimento por Username: " + (banByUsername ? "ATIVADO" : "DESATIVADO"));
    }

    private void loadBannedPlayersCache() {
        playerBanCache.clear();
        ipToBannedAccounts.clear();

        ConfigurationSection playersSection = bannedPlayersConfig.getConfigurationSection("players");
        if (playersSection == null) {
            return;
        }

        for (String uuidKey : playersSection.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidKey);
            } catch (IllegalArgumentException e) {
                getLogger().warning("UUID inválido encontrado no arquivo de ban: " + uuidKey);
                continue;
            }

            String name = playersSection.getString(uuidKey + ".name", "");
            String ip = playersSection.getString(uuidKey + ".ip", "");
            String firstJoin = playersSection.getString(uuidKey + ".firstJoin", "");
            String deathTime = playersSection.getString(uuidKey + ".deathTime", "");

            Map<String, String> playerData = new HashMap<>();
            playerData.put("name", name);
            playerData.put("ip", ip);
            playerData.put("firstJoin", firstJoin);
            playerData.put("deathTime", deathTime);

            playerBanCache.put(uuid, playerData);

            // Mapeia IP para nomes de jogadores
            if (!ip.isEmpty() && !"0.0.0.0".equals(ip)) {
                Set<String> accounts = ipToBannedAccounts.computeIfAbsent(ip, k -> new HashSet<>());
                accounts.add(name.toLowerCase());
            }
        }

        getLogger().info("Memória transitória de banimentos carregada: " + playerBanCache.size() + " jogadores banidos");
    }

    @Override
    public void onDisable() {
        saveConfig();
        saveData();
        playerBanCache.clear();
        ipToBannedAccounts.clear();
        getLogger().info("Plugin DeathLX foi desativado!");
    }

    private void saveData() {
        try {
            bannedPlayersConfig.save(bannedPlayersFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Não foi possível salvar o arquivo de jogadores banidos", e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!isActive) {
            return;
        }

        Player player = event.getEntity();
        String playerIP = "0.0.0.0";

        if (player.getAddress() != null && player.getAddress().getAddress() != null) {
            playerIP = player.getAddress().getAddress().getHostAddress();
        }

        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();

        // Se o jogador já estiver no cache de bans, ignorar
        if (playerBanCache.containsKey(playerUUID)) {
            getLogger().info("Jogador " + playerName + " já está na lista de banidos. Ignorando morte.");
            return;
        }

        // Formatar data e hora
        String currentTime = dateFormat.format(new Date());

        // Obter data de primeiro login (ou usar data atual se não disponível)
        String firstJoin = player.hasPlayedBefore()
                ? dateFormat.format(new Date(player.getFirstPlayed()))
                : currentTime;

        // Adicionar jogador à lista de banidos
        addPlayerToBanList(playerUUID, playerName, playerIP, firstJoin, currentTime);

        // Banir o jogador
        banPlayer(player, playerName, playerIP, firstJoin, currentTime);
    }

    private void addPlayerToBanList(UUID playerUUID, String playerName, String playerIP, String firstJoin, String deathTime) {
        // Atualizar arquivo de configuração
        String playerUUIDString = playerUUID.toString();
        bannedPlayersConfig.set("players." + playerUUIDString + ".name", playerName);
        bannedPlayersConfig.set("players." + playerUUIDString + ".ip", playerIP);
        bannedPlayersConfig.set("players." + playerUUIDString + ".firstJoin", firstJoin);
        bannedPlayersConfig.set("players." + playerUUIDString + ".deathTime", deathTime);
        saveData();

        // Atualizar cache
        Map<String, String> playerData = new HashMap<>();
        playerData.put("name", playerName);
        playerData.put("ip", playerIP);
        playerData.put("firstJoin", firstJoin);
        playerData.put("deathTime", deathTime);

        playerBanCache.put(playerUUID, playerData);

        // Adicionar ao mapa de IPs
        if (!playerIP.isEmpty() && !"0.0.0.0".equals(playerIP)) {
            Set<String> accounts = ipToBannedAccounts.computeIfAbsent(playerIP, k -> new HashSet<>());
            accounts.add(playerName.toLowerCase());
        }

        getLogger().info("Jogador " + playerName + " adicionado à lista de banidos após morte");
    }

    private void banPlayer(Player player, String playerName, String playerIP, String firstJoin, String deathTime) {
        try {
            // Banir por IP
            if (banByIP && !playerIP.isEmpty() && !"0.0.0.0".equals(playerIP)) {
                String banMessage = "Morte durante evento hardcore (DeathLX)";
                getServer().dispatchCommand(getServer().getConsoleSender(),
                        "ban-ip " + playerIP + " " + banMessage);
                getLogger().info("Jogador " + playerName + " foi banido por IP: " + playerIP);
            }

            // Banir por nome de utilizador
            if (banByUsername) {
                String banMessage = "Morte durante evento hardcore (DeathLX)";
                getServer().dispatchCommand(getServer().getConsoleSender(),
                        "ban " + playerName + " " + banMessage);
                getLogger().info("Jogador " + playerName + " foi banido por nick");
            }

            // Kickar o jogador após a morte com mensagem personalizada
            final String banMsg = customBanMessage
                    .replace("%player%", playerName)
                    .replace("%death_time%", deathTime)
                    .replace("%first_join%", firstJoin);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) {
                    player.kickPlayer(banMsg);
                }
            }, kickDelayTicks);

        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Erro ao banir jogador: " + playerName, e);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (!isActive) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();
        String playerIP = event.getAddress().getHostAddress();

        // Verificar ban direto no cache por UUID
        if (playerBanCache.containsKey(playerUUID)) {
            Map<String, String> playerData = playerBanCache.get(playerUUID);
            disallowLogin(event, playerData);
            return;
        }

        // Verificar ban por nome de utilizador (caso o jogador tenha trocado de UUID)
        for (Map<String, String> data : playerBanCache.values()) {
            String storedName = data.get("name");
            if (storedName != null && storedName.equalsIgnoreCase(playerName)) {
                disallowLogin(event, data);
                return;
            }
        }

        // Verificar ban por IP
        if (playerIP != null && !playerIP.isEmpty() && !"0.0.0.0".equals(playerIP) && ipToBannedAccounts.containsKey(playerIP)) {
            // Se o IP está banido, verificar se é o mesmo jogador ou outro com o mesmo IP
            Set<String> bannedAccounts = ipToBannedAccounts.get(playerIP);

            if (bannedAccounts != null && !bannedAccounts.contains(playerName.toLowerCase())) {
                // Possivelmente outra conta do mesmo jogador - encontrar os dados do ban original
                for (Map<String, String> data : playerBanCache.values()) {
                    String storedIp = data.get("ip");
                    if (storedIp != null && storedIp.equals(playerIP)) {
                        disallowLogin(event, data);
                        getLogger().info("Possível conta alternativa de " + playerName + " detectada com IP: " + playerIP);
                        return;
                    }
                }
            }
        }
    }

    private void disallowLogin(PlayerLoginEvent event, Map<String, String> playerData) {
        String name = playerData.get("name");
        String deathTime = playerData.get("deathTime");
        String firstJoin = playerData.get("firstJoin");

        String banMessage = customBanMessage
                .replace("%player%", name)
                .replace("%death_time%", deathTime)
                .replace("%first_join%", firstJoin);

        event.disallow(Result.KICK_BANNED, banMessage);
        getLogger().info("Jogador banido tentou logar: " + name + " / " + event.getAddress().getHostAddress());
    }

    // Classe para gerenciar comandos com TabCompleter
    private class DeathLXCommandExecutor implements CommandExecutor, org.bukkit.command.TabCompleter {

        @Override
        public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, String[] args) {
            if (args == null || args.length == 0) {
                showHelpMenu(sender);
                return true;
            }

            String subCommand = args[0].toLowerCase();

            return switch (subCommand) {
                case "active" -> handleActiveCommand(sender);
                case "banmethod" -> handleBanMethodCommand(sender, args);
                case "revive" -> handleReviveCommand(sender, args);
                case "list" -> handleListCommand(sender, args);
                case "reload" -> handleReloadCommand(sender);
                default -> {
                    sender.sendMessage(ChatColor.RED + "Comando desconhecido. Use /deathlx para ver a lista de comandos.");
                    yield true; // Comando desconhecido ainda é um comando válido para o plugin
                }
            };
        }

        @Override
        public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, String[] args) {
            if (args == null) {
                return new ArrayList<>();
            }

            if (args.length == 0) {
                return new ArrayList<>();
            }

            if (args.length == 1) {
                List<String> completions = new ArrayList<>();
                List<String> commands = Arrays.asList("active", "banmethod", "revive", "list", "reload");

                for (String command : commands) {
                    if (args[0] != null && command.startsWith(args[0].toLowerCase())) {
                        completions.add(command);
                    }
                }

                return completions;
            }

            if (args.length == 2) {
                if (args[0] == null) {
                    return new ArrayList<>();
                }

                switch (args[0].toLowerCase()) {
                    case "banmethod":
                        List<String> completions = new ArrayList<>();
                        List<String> methods = Arrays.asList("ip", "username", "both", "status");

                        for (String method : methods) {
                            if (args[1] != null && method.startsWith(args[1].toLowerCase())) {
                                completions.add(method);
                            }
                        }

                        return completions;
                    case "revive":
                        List<String> playerCompletions = new ArrayList<>();

                        for (Map<String, String> data : playerBanCache.values()) {
                            String name = data.get("name");
                            if (name != null && args[1] != null && name.toLowerCase().startsWith(args[1].toLowerCase())) {
                                playerCompletions.add(name);
                            }
                        }

                        return playerCompletions;
                    case "list":
                        return Arrays.asList("1", "2", "3", "4", "5");
                    default:
                        return new ArrayList<>();
                }
            }

            return new ArrayList<>();
        }

        private void showHelpMenu(CommandSender sender) {
            sender.sendMessage(ChatColor.GOLD + "=== DeathLX - Ajuda do Plugin ===");
            sender.sendMessage(ChatColor.YELLOW + "/deathlx active " + ChatColor.GRAY + "- Ativa/desativa o plugin");
            sender.sendMessage(ChatColor.YELLOW + "/deathlx banmethod <ip|username|both|status> " + ChatColor.GRAY + "- Configura o método de banimento");
            sender.sendMessage(ChatColor.YELLOW + "/deathlx revive <player> " + ChatColor.GRAY + "- Remove o banimento de um jogador");
            sender.sendMessage(ChatColor.YELLOW + "/deathlx list [page] " + ChatColor.GRAY + "- Lista jogadores banidos (paginado)");
            sender.sendMessage(ChatColor.YELLOW + "/deathlx reload " + ChatColor.GRAY + "- Recarrega as configurações do plugin");
        }

        private boolean handleActiveCommand(CommandSender sender) {
            if (!sender.hasPermission("deathlx.active.use")) {
                sender.sendMessage(ChatColor.RED + "Você não tem permissão para usar este comando!");
                return false;
            }

            isActive = !isActive;
            getConfig().set("active", isActive);
            saveConfig();

            sender.sendMessage(ChatColor.GREEN + "Plugin DeathLX " +
                    (isActive ? "ativado" : "desativado") + " com sucesso!");
            return true;
        }

        private boolean handleBanMethodCommand(CommandSender sender, String[] args) {
            if (!sender.hasPermission("deathlx.banmethod.use")) {
                sender.sendMessage(ChatColor.RED + "Você não tem permissão para usar este comando!");
                return false;
            }

            if (args == null || args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Uso correto: /deathlx banmethod <ip|username|both|status>");
                return false;
            }

            String method = args[1].toLowerCase();

            return switch (method) {
                case "ip" -> {
                    banByIP = true;
                    banByUsername = false;
                    sender.sendMessage(ChatColor.GREEN + "Método de banimento definido para: IP apenas");
                    getConfig().set("ban-methods.ip", banByIP);
                    getConfig().set("ban-methods.username", banByUsername);
                    saveConfig();
                    yield true;
                }
                case "username" -> {
                    banByIP = false;
                    banByUsername = true;
                    sender.sendMessage(ChatColor.GREEN + "Método de banimento definido para: Nick apenas");
                    getConfig().set("ban-methods.ip", banByIP);
                    getConfig().set("ban-methods.username", banByUsername);
                    saveConfig();
                    yield true;
                }
                case "both" -> {
                    banByIP = true;
                    banByUsername = true;
                    sender.sendMessage(ChatColor.GREEN + "Método de banimento definido para: IP e Nick");
                    getConfig().set("ban-methods.ip", banByIP);
                    getConfig().set("ban-methods.username", banByUsername);
                    saveConfig();
                    yield true;
                }
                case "status" -> {
                    sender.sendMessage(ChatColor.YELLOW + "Status dos métodos de banimento:");
                    sender.sendMessage(ChatColor.GRAY + "- IP: " + (banByIP ? ChatColor.GREEN + "ATIVADO" : ChatColor.RED + "DESATIVADO"));
                    sender.sendMessage(ChatColor.GRAY + "- Nick: " + (banByUsername ? ChatColor.GREEN + "ATIVADO" : ChatColor.RED + "DESATIVADO"));
                    yield true;
                }
                default -> {
                    sender.sendMessage(ChatColor.RED + "Método inválido. Use ip, username, both ou status.");
                    yield false;
                }
            };
        }

        private boolean handleReviveCommand(CommandSender sender, String[] args) {
            if (!sender.hasPermission("deathlx.revive.use")) {
                sender.sendMessage(ChatColor.RED + "Você não tem permissão para usar este comando!");
                return false;
            }

            if (args == null || args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Uso correto: /deathlx revive <player>");
                return false;
            }

            String targetName = args[1];
            if (targetName == null) {
                sender.sendMessage(ChatColor.RED + "Nome de jogador inválido!");
                return false;
            }

            UUID foundUUID = null;
            String foundIP = null;

            // Buscar no cache primeiro (mais eficiente)
            for (Map.Entry<UUID, Map<String, String>> entry : playerBanCache.entrySet()) {
                Map<String, String> data = entry.getValue();
                String storedName = data.get("name");
                if (storedName != null && storedName.equalsIgnoreCase(targetName)) {
                    foundUUID = entry.getKey();
                    foundIP = data.get("ip");
                    break;
                }
            }

            if (foundUUID != null) {
                // Remover ban de IP se aplicável
                if (foundIP != null && !foundIP.isEmpty() && !"0.0.0.0".equals(foundIP)) {
                    try {
                        getServer().dispatchCommand(getServer().getConsoleSender(), "pardon-ip " + foundIP);
                        getLogger().info("IP desbanido: " + foundIP);

                        // Remover do mapa de IPs
                        Set<String> accounts = ipToBannedAccounts.get(foundIP);
                        if (accounts != null) {
                            accounts.remove(targetName.toLowerCase());
                            if (accounts.isEmpty()) {
                                ipToBannedAccounts.remove(foundIP);
                            }
                        }
                    } catch (Exception e) {
                        getLogger().log(Level.WARNING, "Erro ao desbanir IP: " + foundIP, e);
                    }
                }

                // Remover ban de username
                try {
                    getServer().dispatchCommand(getServer().getConsoleSender(), "pardon " + targetName);
                    getLogger().info("Nick desbanido: " + targetName);
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Erro ao desbanir nick: " + targetName, e);
                }

                // Remover da lista de banidos
                bannedPlayersConfig.set("players." + foundUUID, null);
                saveData();

                // Remover do cache
                playerBanCache.remove(foundUUID);

                sender.sendMessage(ChatColor.GREEN + "Jogador " + ChatColor.GOLD + targetName +
                        ChatColor.GREEN + " foi revivido e desbanido com sucesso!");
                return true;
            } else {
                sender.sendMessage(ChatColor.RED + "Jogador " + targetName + " não foi encontrado na lista de banidos!");
                return false;
            }
        }

        private boolean handleListCommand(CommandSender sender, String[] args) {
            if (!sender.hasPermission("deathlx.list.use")) {
                sender.sendMessage(ChatColor.RED + "Você não tem permissão para usar este comando!");
                return false;
            }

            int page = 1;
            if (args != null && args.length > 1 && args[1] != null) {
                try {
                    page = Integer.parseInt(args[1]);
                    if (page < 1) page = 1;
                } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Número de página inválido!");
                    return false;
                }
            }

            int entriesPerPage = 10;
            List<Map.Entry<UUID, Map<String, String>>> entries = new ArrayList<>(playerBanCache.entrySet());

            int totalPages = (int) Math.ceil((double) entries.size() / entriesPerPage);
            if (totalPages == 0) totalPages = 1;

            if (page > totalPages) {
                page = totalPages;
            }

            sender.sendMessage(ChatColor.GOLD + "=== Jogadores Banidos por Morte === " +
                    ChatColor.GRAY + "[Página " + page + "/" + totalPages + "]");

            if (entries.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "Nenhum jogador banido.");
                return true;
            }

            int startIndex = (page - 1) * entriesPerPage;
            int endIndex = Math.min(startIndex + entriesPerPage, entries.size());

            for (int i = startIndex; i < endIndex; i++) {
                Map.Entry<UUID, Map<String, String>> entry = entries.get(i);
                Map<String, String> data = entry.getValue();

                String name = data.get("name");
                String ip = data.get("ip");
                String deathTime = data.get("deathTime");

                sender.sendMessage(ChatColor.RED + name + ChatColor.GRAY +
                        " - IP: " + ip + " - Morte: " + deathTime);
            }

            if (totalPages > 1) {
                sender.sendMessage(ChatColor.YELLOW + "Use /deathlx list " +
                        (page < totalPages ? (page + 1) : 1) +
                        " para ver a próxima página.");
            }

            return true;
        }

        private boolean handleReloadCommand(CommandSender sender) {
            if (!sender.hasPermission("deathlx.reload.use")) {
                sender.sendMessage(ChatColor.RED + "Você não tem permissão para usar este comando!");
                return false;
            }

            // Recarregar arquivos de configuração
            reloadConfig();
            bannedPlayersConfig = YamlConfiguration.loadConfiguration(bannedPlayersFile);

            // Recarregar configurações
            loadConfiguration();

            // Recarregar cache
            loadBannedPlayersCache();

            sender.sendMessage(ChatColor.GREEN + "Plugin DeathLX recarregado com sucesso!");
            return true;
        }
    }
}