package au.id.rleach.translate;


import au.id.rleach.translate.data.ImmutableLanguageData;
import au.id.rleach.translate.data.LanguageData;
import au.id.rleach.translate.data.TranslateKeys;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.Multimaps;
import com.memetix.mst.language.Language;
import com.memetix.mst.translate.Translate;
import ninja.leaping.configurate.ConfigurationOptions;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import org.slf4j.Logger;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.CommandException;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.data.DataTransactionResult;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.game.GameReloadEvent;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.message.MessageChannelEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.service.permission.PermissionDescription;
import org.spongepowered.api.service.permission.PermissionService;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.chat.ChatTypes;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.text.serializer.TextSerializers;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

import javax.inject.Inject;


@Plugin(id="translatewithbing", name="TranslateWithBing", version="1.2.0", description = "Translates chat using Microsoft Azure")
public class TranslateWithBing {

    @Inject
    @DefaultConfig(sharedRoot = false)
    private ConfigurationLoader<CommentedConfigurationNode> configMan;

    @Inject
    private Logger logger;
    private LocaleToLanguage l2l;

    private final URL jarConfigFile = this.getClass().getResource("default.conf");
    private ConfigurationLoader<CommentedConfigurationNode> loader = HoconConfigurationLoader.builder().setURL(jarConfigFile).build();
    private boolean sendWarningOnJoin = true;

    @Listener
    public void onPreInit(GamePreInitializationEvent event) {
        Sponge.getDataManager().register(LanguageData.class, ImmutableLanguageData.class, LanguageData.BUILDER);
    }

    private Text commandKey = Text.of(Sponge.getRegistry().getTranslationById("options.language").get());
    private Text languageWarning = Text.of(Sponge.getRegistry().getTranslationById("options.languageWarning").get());
    private static final String LANGUAGE_OVERRIDE_PERMISSION = "translate.command.languageoverride";
    private static final String CONFIG_RELOAD_PERMISSION = "translate.command.reload";
    private Map<String, Language> languageChoices = new HashMap<>(20);
    private void initMap(){
        for(final Language lang:Language.values()){
            if(Language.AUTO_DETECT == lang)
                continue;
            try {
                languageChoices.put(lang.getName(lang).replace(' ', '_'),lang);
            } catch (Exception e){
                languageChoices.put(lang.toString().replace(' ', '_'),lang);
            }
        }
    }

    @Listener
    public void reloadGame(final GameReloadEvent reloadEvent){
        reload();
    }

    @Listener
    public void onPlayerJoin(final ClientConnectionEvent.Join event){
        if(!this.sendWarningOnJoin) return;
        if(Sponge.getServer().getOnlinePlayers().stream().anyMatch(p->!p.getLocale().equals(event.getTargetEntity().getLocale()))) {
            Sponge.getServer().getBroadcastChannel().send(languageWarning);
        }
    }

    @Listener
    public void onCommandInitTime(final GameInitializationEvent event) {
        initMap();
        final CommandSpec overrideLanguageSpec = CommandSpec.builder()
                .arguments(GenericArguments.playerOrSource(Text.of("player")), GenericArguments.optional(GenericArguments.choices(commandKey, languageChoices)))
                .permission(LANGUAGE_OVERRIDE_PERMISSION)
                .executor((src, context) -> {
                    Optional<Player> p = context.getOne("player");
                    Optional<Language> opt = context.getOne(commandKey);
                    if (!p.isPresent()) {
                        return CommandResult.empty();
                    }
                    if (opt.isPresent()) {
                        DataTransactionResult result = p.get().offer(new LanguageData(opt.get().toString()));
                        if (result.isSuccessful()) {
                            try {
                                src.sendMessage(Text.of("Players language set to : ", opt.get().getName(opt.get())));
                            } catch (Exception e) {
                                throw new CommandException(Text.of(e.getMessage()));
                            }
                            return CommandResult.success();
                        } else {
                            src.sendMessage(Text.of("Invalid Language"));
                            return CommandResult.empty();
                        }
                    } else {
                        LanguageData data = p.get().get(LanguageData.class).orElse(new LanguageData());
                        Language lang = Language.fromString(data.language().get());
                        try {
                            src.sendMessage(Text.of(commandKey, " ", lang.getName(lang)));
                        } catch (Exception e) {
                            src.sendMessage(Text.of(commandKey, " ", lang.toString()));
                        }
                        return CommandResult.success();
                    }
                })
                .build();
        final CommandSpec reloadConfigSpec = CommandSpec.builder()
                .permission(CONFIG_RELOAD_PERMISSION)
                .executor((src,args)->{return doReload(src,args);})
                .build();

        Sponge.getCommandManager().register(this, overrideLanguageSpec, "language");
        Sponge.getCommandManager().register(this, reloadConfigSpec, "reloadTranslate");
        final Optional<PermissionService> permissionService = Sponge.getGame().getServiceManager().provide(PermissionService.class);
        permissionService.ifPresent(ps->{
            final Optional<PermissionDescription.Builder> builder = ps.newDescriptionBuilder(this);
            builder.ifPresent(descBuilder ->
                    descBuilder
                        .assign(PermissionDescription.ROLE_USER, true)
                        .id(LANGUAGE_OVERRIDE_PERMISSION)
                        .description(Text.of("For command /langauge for overriding TranslateWithBing language."))
                        .register());
            final Optional<PermissionDescription.Builder> builder2 = ps.newDescriptionBuilder(this);
            builder2.ifPresent(descBuilder->
                    descBuilder
                        .assign(PermissionDescription.ROLE_ADMIN, true)
                        .id(CONFIG_RELOAD_PERMISSION)
                        .description(Text.of("Reloads the Translate configuration"))
                        .register());
        });

    }

    private CommandResult doReload(final CommandSource src, final CommandContext args) {
        reload();
        src.sendMessage(Text.of("Reloaded translator config"));
        return CommandResult.success();
    }

    private void reload() {
        setupPlugin();
    }

    @Listener
    public final void serverStarted(final GamePreInitializationEvent event){
        setupPlugin();
    }

    private void setupPlugin(){
        l2l = new LocaleToLanguage();
        CommentedConfigurationNode rootNode = null;
        CommentedConfigurationNode defNode = null;
        try {
            rootNode = configMan.load(ConfigurationOptions.defaults().setShouldCopyDefaults(true));
            defNode = loader.load();
            rootNode = rootNode.mergeValuesFrom(defNode);
            configMan.save(rootNode);
        } catch (final IOException e) {
            logger.error("Unable to read config ",e);
        }

        final CommentedConfigurationNode id = rootNode.getNode("ClientID");
        final CommentedConfigurationNode secret = rootNode.getNode("ClientSecret");
        final String sID = id.getString(defNode.getNode("ClientID").getString());
        final String sSecret = secret.getString(defNode.getNode("ClientSecret").getString());
        final Boolean sSendWarningOnJoin = rootNode.getNode("SendWarningOnJoin").getBoolean(true);
        if("UNSET".equals(sSecret)) {
            throw new RuntimeException("You need to register a ClientID & Client Secret to use this plugin, see https://msdn.microsoft.com/en-us/library/mt146806.aspx and fill in the config");
        }
        try {
            sendWarningOnJoin = Preconditions.checkNotNull(sSendWarningOnJoin);
            Translate.setClientId(Preconditions.checkNotNull(sID));
            Translate.setClientSecret(Preconditions.checkNotNull(sSecret));
        } catch (final RuntimeException e){
            throw new RuntimeException("You need to register a ClientID & Client Secret to use this plugin, see https://msdn.microsoft.com/en-us/library/mt146806.aspx and fill in the config");
        }

        //Translate.setContentType("text/html");
        Translate.setContentType("text/plain");
    }

    @Listener(order = Order.LAST)
    public void chatEvent(final MessageChannelEvent.Chat chat, @First final Player player){
        final Iterator<Player> playerI = chat.getChannel().get()
                                 .getMembers().stream()
                                 .filter(messageReceiver -> messageReceiver instanceof Player)
                                 .map(p -> (Player) p)
                                 .iterator();
        final ImmutableListMultimap<Language, Player> multiMap = Multimaps.index(playerI, this::languageFromPlayer);
        final Text message = chat.getMessage();
        sendTranslatedMessages(player, multiMap, message);
    }

    private Language languageFromPlayer(final Player p){
        final String dataLang = p.get(TranslateKeys.Language).orElse("");
        if(dataLang.isEmpty()){
            return l2l.map.getOrDefault(p.getLocale(), Language.AUTO_DETECT);
        } else {
            return Language.fromString(dataLang);
        }
    }

    private void sendTranslatedMessages(final Player from, final ImmutableListMultimap<Language, Player> multiMap, final Text message){
        final Language fromLang = languageFromPlayer(from);
        final Task submit = Sponge.getScheduler().createTaskBuilder()
                .async()
                .name("Chat Translate Task")
                .execute(() -> {
                    multiMap.keys().stream()
                        .distinct()
                        .filter(to-> Language.AUTO_DETECT != to)
                        .filter(to-> to != fromLang).forEach(
                        to -> {
                            String html = "";
                            String out2 = "";
                            try {
                                html = TextSerializers.LEGACY_FORMATTING_CODE.serialize(message);
                                String out = Translate.execute(html, fromLang, to);
                                out2 = out;
                                multiMap.get(to).stream().forEach(p->p.sendMessage(ChatTypes.CHAT, Text.of(TextColors.GRAY,"⚑", TextSerializers.LEGACY_FORMATTING_CODE.deserialize(out))));

                            } catch (Exception e) {
                                this.logger.error("threw an exception while parsing response", e);
                                this.logger.error("\n\n{}->{}\nbefore:\n{}\n\nafter: \n{}", from, to, html, out2);
                            }
                        }
                    );

                })
                .submit(this);
    }
}
