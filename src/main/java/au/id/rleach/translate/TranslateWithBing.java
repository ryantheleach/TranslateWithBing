package au.id.rleach.translate;

import au.id.rleach.translate.data.ImmutableLanguageData;
import au.id.rleach.translate.data.LanguageData;
import au.id.rleach.translate.data.LanguageDataManipulatorBuilder;
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
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.args.GenericArguments;
import org.spongepowered.api.command.spec.CommandSpec;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.data.DataTransactionResult;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.Order;
import org.spongepowered.api.event.filter.cause.First;
import org.spongepowered.api.event.game.state.GameInitializationEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.message.MessageChannelEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.chat.ChatTypes;
import org.spongepowered.api.text.format.TextColors;
import org.spongepowered.api.text.serializer.TextSerializers;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URL;
import java.util.*;


@Plugin(id="TranslateWithBing", name="TranslateWithBing", version="0.1.0")
public class TranslateWithBing {

    @Inject
    @DefaultConfig(sharedRoot = false)
    private ConfigurationLoader<CommentedConfigurationNode> configMan;

    @Inject
    Logger logger;
    private LocaleToLanguage l2l;

    private URL jarConfigFile = this.getClass().getResource("default.conf");
    private ConfigurationLoader<CommentedConfigurationNode> loader = HoconConfigurationLoader.builder().setURL(jarConfigFile).build();

    @Listener
    public void onPreInit(GamePreInitializationEvent event) {
        Sponge.getDataManager().register(LanguageData.class, ImmutableLanguageData.class, new LanguageDataManipulatorBuilder());
    }

    Text commandKey = Text.of(Sponge.getRegistry().getTranslationById("options.language").get());
    Text languageWarning = Text.of(Sponge.getRegistry().getTranslationById("options.languageWarning").get());


    public final String languageOverridePermission = "translate.command.languageoverride";
    Map<String, Language> languageChoices = new HashMap<>(20);
    public void initMap(){
        {
            for(Language l:Language.values()){
                try {
                    String underscore = l.getName(l).replace(" ","_");
                    languageChoices.put(underscore,l);
                } catch (Exception e){
                }
            }
        }
    }
    @Listener
    public void onCommandInitTime(GameInitializationEvent event){
        initMap();
        CommandSpec overrideLanguageSpec = CommandSpec.builder()
                .arguments(GenericArguments.playerOrSource(Text.of("player")) , GenericArguments.optional(GenericArguments.choices(commandKey, languageChoices)))
                .permission(languageOverridePermission)
                .executor((src, context)->{
                    Optional<Player> p = context.getOne("player");
                    Optional<Language> opt = context.getOne("options.language");
                    if(!p.isPresent()) return CommandResult.empty();
                    if(opt.isPresent()) {
                        DataTransactionResult result = p.get().offer(new LanguageData(opt.get().toString()));
                        if (result.isSuccessful()) {
                            try {
                                src.sendMessage(Text.of("Players language set to :", opt.get().getName(opt.get())));
                            } catch (Exception e) {}
                            return CommandResult.success();
                        }
                        else {
                            src.sendMessage(Text.of("Invalid Language"));
                            return CommandResult.empty();
                        }
                    } else {
                        LanguageData data = p.get().get(LanguageData.class).orElse(new LanguageData());
                        src.sendMessage(Text.of(commandKey," ", data.language().get()));
                        return CommandResult.success();
                    }
                })
                .build();
        logger.warn(Sponge.getCommandManager().register(this,overrideLanguageSpec,"language").toString());
    }

    @Listener
    public void serverStarted(GamePreInitializationEvent event){
        l2l = new LocaleToLanguage();
        CommentedConfigurationNode rootNode = null;
        CommentedConfigurationNode defNode = null;
        try {
            rootNode = configMan.load(ConfigurationOptions.defaults().setShouldCopyDefaults(true));
            defNode = loader.load();
            rootNode = rootNode.mergeValuesFrom(defNode);
            configMan.save(rootNode);
        } catch (IOException e) {
            logger.error("Unable to read config ",e);
        }

        CommentedConfigurationNode id = rootNode.getNode("ClientID");
        CommentedConfigurationNode secret = rootNode.getNode("ClientSecret");
        String sID = id.getString(defNode.getNode("ClientID").getString());
        String sSecret = secret.getString(defNode.getNode("ClientSecret").getString());
        if(sSecret.equals("UNSET")) throw new RuntimeException("You need to register a ClientID & Client Secret to use this plugin, see https://msdn.microsoft.com/en-us/library/mt146806.aspx and fill in the config");
        try {
            Translate.setClientId(Preconditions.checkNotNull(sID));
            Translate.setClientSecret(Preconditions.checkNotNull(sSecret));
        } catch (Exception e){
            throw new RuntimeException("You need to register a ClientID & Client Secret to use this plugin, see https://msdn.microsoft.com/en-us/library/mt146806.aspx and fill in the config");
        }

        Translate.setContentType("text/html");
    }

    @Listener(order = Order.LAST)
    public void chatEvent(MessageChannelEvent.Chat chat, @First Player player){
        Locale locale = player.getLocale();

        Iterator<Player> x = chat.getChannel().get()
                                 .getMembers().stream()
                                 .filter(messageReceiver -> messageReceiver instanceof Player)
                                 .map(p -> (Player) p)
                                 .iterator();
        ImmutableListMultimap<Language, Player> multiMap = Multimaps.index(x, p -> {
            return l2l.map.getOrDefault(p.getLocale(), Language.AUTO_DETECT);
        });
        Optional<Text> message = chat.getMessage();
        if(message.isPresent())
            sendTranslatedMessages(locale,multiMap,message.get());
    }

    public void sendTranslatedMessages(Locale locale, ImmutableListMultimap<Language, Player> multiMap, Text message){
        Language from = this.l2l.map.getOrDefault(locale, Language.AUTO_DETECT);
        Task submit = Sponge.getScheduler().createTaskBuilder()
                .async()
                .execute(() -> {
                    multiMap.keys().stream()
                            .distinct()
                            .filter(to->!to.equals(Language.AUTO_DETECT))
                            .filter(to->!to.equals(from)).forEach(
                            to -> {
                                String html = "";
                                String out2 = "";
                                try {
                                    html = TextSerializers.LEGACY_FORMATTING_CODE.serialize(message);
                                    String out = Translate.execute(html, from, to);
                                    out2 = out;
                                    multiMap.get(to).stream().forEach(p->p.sendMessage(ChatTypes.CHAT, Text.of(TextColors.GRAY,"⚑", TextSerializers.LEGACY_FORMATTING_CODE.deserialize(out))));

                                } catch (Exception e) {
                                    logger.error("threw an exception while parsing xml", e);
                                    logger.error("\n\n"+from.toString()+"->"+to.toString()+"\nbefore:\n"+html + "\n\nafter: \n" + out2);
                                }
                            }
                    );

                }).submit(this);
    }
}