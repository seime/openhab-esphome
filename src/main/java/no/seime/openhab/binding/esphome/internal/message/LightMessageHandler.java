package no.seime.openhab.binding.esphome.internal.message;

import static org.openhab.core.library.CoreItemFactory.*;

import java.util.Arrays;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.PercentType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.type.ChannelKind;
import org.openhab.core.thing.type.ChannelType;
import org.openhab.core.types.Command;
import org.openhab.core.types.StateDescription;
import org.openhab.core.util.ColorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.LightCommandRequest;
import io.esphome.api.LightStateResponse;
import io.esphome.api.ListEntitiesLightResponse;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.EntityTypes;
import no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

public class LightMessageHandler extends AbstractMessageHandler<ListEntitiesLightResponse, LightStateResponse> {

    private final Logger logger = LoggerFactory.getLogger(LightMessageHandler.class);

    public LightMessageHandler(ESPHomeHandler handler) {
        super(handler);
    }

    private final static String CHANNEL_LIGHT = "light";
    private final static String CHANNEL_EFFECTS = "effects";

    @Override
    public void handleCommand(Channel channel, Command command, int key) throws ProtocolAPIError {

        String subCommand = (String) channel.getConfiguration()
                .get(BindingConstants.CHANNEL_CONFIGURATION_ENTITY_FIELD);
        switch (subCommand) {
            case CHANNEL_LIGHT -> {

                Set<LightColorCapability> capabilities = deserialize(
                        (String) channel.getConfiguration().get("capabilities"));

                if (command instanceof HSBType hsb && capabilities.contains(LightColorCapability.RGB)) {
                    PercentType[] percentTypes = ColorUtil.hsbToRgbPercent(hsb);

                    LightCommandRequest.Builder builder = LightCommandRequest.newBuilder().setKey(key)
                            .setRed(percentTypes[0].floatValue() / 100).setGreen(percentTypes[1].floatValue() / 100)
                            .setBlue(percentTypes[2].floatValue() / 100).setHasRgb(true);

                    // Turn on/off light if necessary
                    builder.setState(hsb.getBrightness().floatValue() > 0).setHasState(true);

                    // Adjust brightness
                    builder.setBrightness(hsb.getBrightness().floatValue() / 100).setHasBrightness(true);

                    handler.sendMessage(builder.build());
                } else if (command instanceof PercentType percentType
                        && capabilities.contains(LightColorCapability.BRIGHTNESS)) {
                    LightCommandRequest.Builder builder = LightCommandRequest.newBuilder().setKey(key);

                    // Only set brightness if it's greater than 0, otherwise turn off the light
                    if (percentType.floatValue() > 0) {
                        builder.setBrightness(percentType.floatValue() / 100).setHasBrightness(true).setState(true)
                                .setHasState(true);
                    } else {
                        builder.setState(false).setHasState(true);
                    }

                    handler.sendMessage(builder.build());
                } else if (command instanceof OnOffType onOffType
                        && capabilities.contains(LightColorCapability.ON_OFF)) {
                    handler.sendMessage(LightCommandRequest.newBuilder().setKey(key).setState(onOffType == OnOffType.ON)
                            .setHasState(true).build());
                } else {
                    logger.warn("[{}] Unsupported command {} for channel {}", handler.getLogPrefix(), command,
                            channel.getUID());
                }
            }
            case CHANNEL_EFFECTS -> {
                if (command instanceof StringType stringType) {
                    handler.sendMessage(LightCommandRequest.newBuilder().setKey(key).setEffect(stringType.toString())
                            .setHasEffect(true).setState(true).setHasState(true).build());
                } else {
                    logger.warn("[{}] Unsupported command {} for channel {}", handler.getLogPrefix(), command,
                            channel.getUID());
                }
            }
        }
    }

    public void buildChannels(ListEntitiesLightResponse rsp) {
        Configuration configuration = configuration(EntityTypes.LIGHT, rsp.getKey(), CHANNEL_LIGHT);
        SortedSet<LightColorCapability> capabilities = decodeCapabilities(rsp);
        configuration.put("capabilities", serialize(capabilities));

        String icon = getChannelIcon(rsp.getIcon(), "light");

        if (capabilities.contains(LightColorCapability.RGB)) {
            // Go for a single Color channel
            ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), COLOR, Set.of("Light"), icon,
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());

            Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                    .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withAcceptedItemType(COLOR).withConfiguration(configuration).build();

            super.registerChannel(channel, channelType);
        } else if (capabilities.contains(LightColorCapability.BRIGHTNESS)) {
            // Go for a single Dimmer channel
            ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), DIMMER, Set.of("Light"), icon,
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());

            Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                    .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withAcceptedItemType(DIMMER).withConfiguration(configuration).build();

            super.registerChannel(channel, channelType);
        } else if (capabilities.contains(LightColorCapability.ON_OFF)) {
            // Go for a single Switch channel
            ChannelType channelType = addChannelType(rsp.getUniqueId(), rsp.getName(), SWITCH, Set.of("Light"), icon,
                    rsp.getEntityCategory(), rsp.getDisabledByDefault());

            Channel channel = ChannelBuilder.create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId()))
                    .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withAcceptedItemType(SWITCH).withConfiguration(configuration).build();

            super.registerChannel(channel, channelType);
        }

        if (rsp.getEffectsCount() > 0) {
            // Create effects channel
            ChannelType channelType = addChannelType(rsp.getUniqueId() + "-effects", rsp.getName(), STRING,
                    Set.of("Setpoint"), icon, rsp.getEntityCategory(), rsp.getDisabledByDefault());
            StateDescription stateDescription = optionListStateDescription(rsp.getEffectsList());

            Channel channel = ChannelBuilder
                    .create(new ChannelUID(handler.getThing().getUID(), rsp.getObjectId() + "-effects"))
                    .withLabel(rsp.getName()).withKind(ChannelKind.STATE).withType(channelType.getUID())
                    .withAcceptedItemType(STRING)
                    .withConfiguration(configuration("Light", rsp.getKey(), CHANNEL_EFFECTS)).build();

            super.registerChannel(channel, channelType, stateDescription);

        }
    }

    public static String serialize(SortedSet<LightColorCapability> capabilities) {
        return capabilities.stream().map(e -> e.name()).collect(Collectors.joining(","));
    }

    public static SortedSet<LightColorCapability> deserialize(String capabilities) {
        return capabilities == null ? new TreeSet<>()
                : Arrays.stream(capabilities.split(",")).map(LightColorCapability::valueOf)
                        .collect(Collectors.toCollection(TreeSet::new));
    }

    public void handleState(LightStateResponse rsp) {
        findChannelByKeyAndField(rsp.getKey(), CHANNEL_LIGHT).ifPresent(channel -> {
            Configuration configuration = channel.getConfiguration();
            SortedSet<LightColorCapability> capabilities = deserialize((String) configuration.get("capabilities"));
            if (capabilities.contains(LightColorCapability.RGB)) {
                // Convert to color
                HSBType hsbType = ColorUtil.rgbToHsb(
                        new int[] { (int) rsp.getRed() * 255, (int) rsp.getGreen() * 255, (int) rsp.getBlue() * 255 });

                // If off, set brightness to 0
                if (!rsp.getState()) {
                    hsbType = new HSBType(hsbType.getHue(), hsbType.getSaturation(), new PercentType(0));
                } else {
                    // Adjust brightness
                    hsbType = new HSBType(hsbType.getHue(), hsbType.getSaturation(),
                            new PercentType((int) (rsp.getBrightness() * 100)));
                }

                handler.updateState(channel.getUID(), hsbType);
            } else if (capabilities.contains(LightColorCapability.BRIGHTNESS)) {
                PercentType percentType = new PercentType((int) (rsp.getState() ? rsp.getBrightness() * 100 : 0));
                handler.updateState(channel.getUID(), percentType);
            } else if (capabilities.contains(LightColorCapability.ON_OFF)) {
                OnOffType onOffType = rsp.getState() ? OnOffType.ON : OnOffType.OFF;
                handler.updateState(channel.getUID(), onOffType);
            }
        });

        findChannelByKeyAndField(rsp.getKey(), CHANNEL_EFFECTS).ifPresent(channel -> {
            handler.updateState(channel.getUID(), new StringType(rsp.getEffect()));
        });
    }

    private SortedSet<LightColorCapability> decodeCapabilities(ListEntitiesLightResponse rsp) {
        SortedSet<LightColorCapability> capabilities = new TreeSet<>();
        for (Integer bitset : rsp.getSupportedColorModesValueList()) {
            if ((bitset & (1 << 0)) != 0) {
                capabilities.add(LightColorCapability.ON_OFF);
            }
            if ((bitset & (1 << 1)) != 0) {
                capabilities.add(LightColorCapability.BRIGHTNESS);
            }
            if ((bitset & (1 << 2)) != 0) {
                capabilities.add(LightColorCapability.WHITE);
            }
            if ((bitset & (1 << 3)) != 0) {
                capabilities.add(LightColorCapability.COLOR_TEMPERATURE);
            }
            if ((bitset & (1 << 4)) != 0) {
                capabilities.add(LightColorCapability.COLD_WARM_WHITE);
            }
            if ((bitset & (1 << 5)) != 0) {
                capabilities.add(LightColorCapability.RGB);
            }
        }
        return capabilities;
    }

    public enum LightColorCapability {
        ON_OFF,
        BRIGHTNESS,
        WHITE,
        COLOR_TEMPERATURE,
        COLD_WARM_WHITE,
        RGB
    }
}
