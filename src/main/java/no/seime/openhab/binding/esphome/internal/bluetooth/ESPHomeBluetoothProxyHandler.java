package no.seime.openhab.binding.esphome.internal.bluetooth;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.bluetooth.AbstractBluetoothBridgeHandler;
import org.openhab.binding.bluetooth.BluetoothAddress;
import org.openhab.core.thing.*;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.esphome.api.BluetoothLEAdvertisementResponse;
import no.seime.openhab.binding.esphome.internal.BindingConstants;
import no.seime.openhab.binding.esphome.internal.handler.ESPHomeHandler;

@NonNullByDefault
public class ESPHomeBluetoothProxyHandler extends AbstractBluetoothBridgeHandler<ESPHomeBluetoothDevice> {

    private final ThingRegistry thingRegistry;

    @Nullable
    private ScheduledFuture<?> registrationFuture;

    private final Logger logger = LoggerFactory.getLogger(ESPHomeBluetoothProxyHandler.class);

    private List<ESPHomeHandler> espHomeHandlers = new ArrayList<>();

    /**
     * Creates a new instance of this class for the {@link Thing}.
     *
     * @param thing the thing that should be handled, not null
     * @param thingRegistry
     */
    public ESPHomeBluetoothProxyHandler(Bridge thing, ThingRegistry thingRegistry) {
        super(thing);
        this.thingRegistry = thingRegistry;
    }

    @Override
    public void initialize() {
        super.initialize();
        updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Looking for BLE enabled ESPHome devices");

        registrationFuture = scheduler.scheduleWithFixedDelay(this::updateESPHomeDeviceList, 0, 5, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        registrationFuture.cancel(true);
        espHomeHandlers.forEach(ESPHomeHandler::stopListeningForBLEAdvertisements);
        espHomeHandlers.clear();
        super.dispose();
    }

    private synchronized void updateESPHomeDeviceList() {

        logger.debug("Updating list of ESPHome devices");
        // Get all ESPHome devices
        // For each device, check if it has BLE enabled, is enabled and ONLINE
        // If so, enable registration of BLE advertisements
        // If not, remove from list of devices

        // First clean up any disposed handlers or non-ONLINE handlers
        List<ESPHomeHandler> inactiveHandlers = espHomeHandlers.stream()
                .filter(handler -> handler.isDisposed() || !handler.getThing().getStatus().equals(ThingStatus.ONLINE))
                .toList();
        espHomeHandlers.removeAll(inactiveHandlers);
        inactiveHandlers.stream().forEach(handler -> {
            try {
                handler.stopListeningForBLEAdvertisements();
            } catch (Exception e) {
                // Swallow
            }
        });

        List<Thing> esphomeThings = thingRegistry.stream()
                .filter(thing -> thing.getThingTypeUID().equals(BindingConstants.THING_TYPE_DEVICE)).toList();
        for (Thing esphomeThing : esphomeThings) {
            if (esphomeThing.isEnabled() && esphomeThing.getStatus().equals(ThingStatus.ONLINE)) {
                if (esphomeThing.getConfiguration().get("enableBluetoothProxy") == Boolean.TRUE) {
                    // Enable registration of BLE advertisements

                    ESPHomeHandler handler = (ESPHomeHandler) esphomeThing.getHandler();

                    if (!espHomeHandlers.contains(handler)) {
                        handler.listenForBLEAdvertisements(this);
                        espHomeHandlers.add(handler);
                    }
                }
            }
        }

        if (espHomeHandlers.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.NONE, String.format(
                    "Found no ESPHome devices configured for Bluetooth proxy support. Make sure your ESPHome things are online and have the 'enableBluetoothProxy' option set to 'true'"));

        } else {
            updateStatus(ThingStatus.ONLINE, ThingStatusDetail.NONE, String
                    .format("Found %d ESPHome devices configured for Bluetooth proxy support", espHomeHandlers.size()));
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    @Override
    protected ESPHomeBluetoothDevice createDevice(BluetoothAddress address) {
        return new ESPHomeBluetoothDevice(this, address);
    }

    private BluetoothAddress createAddress(long address) {
        String hexDigits = HexFormat.of().toHexDigits(address);

        StringBuilder addressBuilder = new StringBuilder();

        // Skip first 2 bytes as addresses are 48 bits and not 64 bits
        for (int i = 4; i < hexDigits.length(); i += 2) {
            addressBuilder.append(hexDigits.substring(i, i + 2));
            if (i < hexDigits.length() - 2) {
                addressBuilder.append(":");
            }
        }

        return new BluetoothAddress(addressBuilder.toString().toUpperCase());
    }

    public void handleAdvertisement(BluetoothLEAdvertisementResponse rsp) {

        try {
            BluetoothAddress address = createAddress(rsp.getAddress());
            ESPHomeBluetoothDevice device = getDevice(address);

            logger.debug("Received BLE advertisement from device {}", address);

            device.setName(rsp.getName());
            device.setRssi(rsp.getRssi());

            rsp.getManufacturerDataList().stream().findFirst().ifPresent(manufacturerData -> {
                String uuid = manufacturerData.getUuid();
                byte[] bytes = HexFormat.of().parseHex(uuid.substring(2));
                int manufacturerId = (bytes[0] & 0xFF) << 8 | (bytes[1] & 0xFF);
                device.setManufacturerId(manufacturerId);

                logger.debug("Manufacturer data UUID: {}", uuid);

            });

            deviceDiscovered(device);

            device.handleAdvertisementPacket(rsp);

        } catch (Exception e) {
            logger.warn("Error handling BLE advertisement", e);
        }
    }

    @Override
    public @Nullable BluetoothAddress getAddress() {
        // Return adapter/ESPHome address
        return null;
    }
}
