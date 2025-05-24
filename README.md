# ESPHome Binding for openHAB

### Docs updated 2025-05-24.

<img src="logo.png" width="200"/>

[<img src="https://github.com/seime/support-me/blob/main/openHAB_workswith.png" width=300>](https://www.openhab.org)

[<img src="https://github.com/seime/support-me/blob/main/beer_me.png" width=150>](https://buymeacoffee.com/arnes)

This binding makes [ESPHome](https://esphome.io) devices available in openHAB through the ESPHome Home Assistant Native
API. This is an
alternative to using MQTT and/or running Home Assistant in addition to openHAB.

It does _NOT_ provide any webpage for managing the ESP themselves. Use
the [ESPHome dashboard](https://esphome.io/guides/installing_esphome.html) for that.

<img src="esphomedashboard.png" alt="ESPHome dashboard" width="30%"/>

Benefits of using the native API over MQTT:

- Very tight integration with openHAB, state patterns, options, icons etc fully integrated
- Robust and reliable communication - 2 way keep-alive pings at fairly short intervals lets you know if the device has
  gone offline
- No need for an MQTT broker (but that is nice to have anyway for other things :))
- Slightly faster than messaging over MQTT (according to the ESPHome documentation)

Read more here: https://esphome.io/components/api#advantages-over-mqtt

## Getting started for non ESPHome users

1. [Install ESPHome](https://esphome.io/guides/installing_esphome)
2. Create an ESPHome configuration for your device
3. Flash the device with the ESPHome firmware
4. Install the openHAB ESPHome binding by copying the jar file
   here https://github.com/seime/openhab-esphome/releases/tag/latest_oh4 into your `addons` folder, or by installing
   from the Marketplace https://community.openhab.org/t/esphome-binding-for-the-native-api/146849
5. Wait for discovery to find your device - or add manually in a thing file.

> **Note:** Remember to edit your things and add the `encryptionKey` .

## Discovery

The binding uses mDNS to automatically discover devices on the network.

## Thing Configuration

### `device` Thing Configuration

| Name                   | Type      | Description                                                                                                                                              | Default  | Required | Advanced |
|------------------------|-----------|----------------------------------------------------------------------------------------------------------------------------------------------------------|----------|----------|----------|
| `deviceId`             | `text`    | Expected name of ESPHome. Used to ensure that we're communicating with the correct device. Use value from `esphome.name` in ESPHome device configuration |          | yes      | no       |
| `hostname`             | `text`    | Hostname or IP address of the device. Typically something like 'myboard.local'                                                                           |          | yes      | no       |
| `port`                 | `integer` | IP Port of the device                                                                                                                                    | 6053     | no       | no       |
| `encryptionKey`        | `text`    | Encryption key as defined in `api: encryption: key: <BASE64ENCODEDKEY>`. See https://esphome.io/components/api#configuration-variables                   |          | yes      | no       |
| `pingInterval`         | `integer` | Seconds between sending ping requests to device to check if alive                                                                                        | 10       | no       | yes      |
| `maxPingTimeouts`      | `integer` | Number of missed ping requests before deeming device unresponsive.                                                                                       | 4        | no       | yes      |
| `reconnectInterval`    | `integer` | Seconds between reconnect attempts when connection is lost or the device restarts.                                                                       | 10       | no       | yes      |
| `logPrefix`            | `text`    | Log prefix to use for this device.                                                                                                                       | deviceId | no       | yes      |
| `deviceLogLevel`       | `text`    | ESPHome device log level to stream from the device.                                                                                                      | NONE     | no       | yes      |
| `enableBluetoothProxy` | `boolean` | Allow this device to proxy Bluetooth traffic. Requires ESPHome device to be configured with `bluetooth_proxy`                                            | false    | no       | yes      |

## Channels

Channels are auto-generated based on actual device configuration. Bring the device online, and the binding will
interrogate the device and create channels based on the device configuration.

## Full Example file example

### Thing Configuration for ESPHome device

```
esphome:device:garage-opener  "Garage ESP32" [deviceId="garage-opener", hostname="garage-opener.local", encryptionKey="JVWAgubY1nCe3x/5xeyMBfaN9y68OOUMh5dACIeVmjk=", pingInterval=10, maxPingTimeouts=4, reconnectInterval=10, logPrefix="garage", deviceLogLevel="INFO"]
```

### Item Configuration

```
Number:Temperature Garage_Temperature "Temperature" <temperature>   {channel="esphome:device:garage-opener:temperature"}
Number:Dimensionless Garage_Humidity "Humidity"     <humidity>      {channel="esphome:device:garage-opener:humidity"}
Switch Garage_Switch "Relay"                        <switch>        {channel="esphome:device:garage-opener:relay_4"}
```

## FAQ

### My hostname field suddenly changed to xxxx.local?

> Openhab updates thing configuration based on mDNS messages. There is currently no way to avoid this, but there are 2
> workarounds
> * Use a different thingUID from the one discovered, ie `esphome:device:fridge` -> `esphome:device:fridge-esp`
> * Use file based configuration as they are read-only
    > See https://github.com/seime/openhab-esphome/issues/1 . TLDR: A "feature" in openHAB.

### I cannot connect to my device

I get errors like
```[WARN ] [phome.internal.handler.ESPHomeHandler] - [mydevice] Error initial connection no.seime.openhab.binding.esphome.internal.comm.ProtocolAPIError: Failed to connect to 'XXXX.local.' port 6053```
> See previous question

### openHAB loose connection to my device

with log messages like
`[WARN ] [home.internal.handler.ESPHomeHandler] - [esphome-deviceId] Ping responses lacking. Waited 4 times 10 seconds, total of 40. Assuming connection lost and disconnecting`
`[INFO ] [ESPHome Socket Reader] [home.internal.comm.ESPHomeConnection] - [esphome-deviceId] Disconnecting socket.`

> This can be caused by a flaky device or network connection. It can also be caused by device overload, causing it to
> drop messages. If you are using the have a lot of chatty devices or using the bluetooth proxy, the ESP might overload
> it's TCP
> send buffer. To check if this is your problem, set the `api` component logger to `VERBOSE` and look for
`Cannot send message because of TCP buffer space`.
> If this happens, get a more powerful ESP or reduce how often it sends data.

> If you have the `uptime` sensor in your ESPHome configuration, you can use that to monitor the device uptime. If the
> device uptime is increasing while openHAB reports the device as offline, it is likely a network issue.

### I get warnings like

`Unit 'XXXX' unknown to openHAB, returning DecimalType for state '1234.0' on channel 'esphome:device:8dcf64d482:my_channel_name`

> The unit is reported by the ESPHome device, but it doesn't match anything known to openHAB.
>
> Solution: Add a valid `unit_of_measurement` to your entity in the ESPHome configuration. Use `unit_of_measurement: ""`
> to remove the unit altogether from the entity.

### I get warnings like

`No device_class reported by sensor '<name of sensor>'. Add device_class to sensor configuration in ESPHome. Defaulting to plain Number without dimension`

> This is because the ESP sensor does not report a `device_class`. This field is used to determine item and category
> type in openHAB.
> Solution: Specify a `device_class` to your ESPHome configuration. Example: <br/>
> ![img.png](esphomeconfig_deviceclass.png)
> <br/>See https://developers.home-assistant.io/docs/core/entity/sensor/#available-device-classes for valid device_class
> values (**use lowercase values**)
> Also note that you may override default device_class by specifying `device_class: ""` to remove any device class from
> the sensor.

Also see https://community.openhab.org/t/esphome-binding-for-the-native-api/146849/1 for more information.

## Bluetooth proxy support

It is now possible to use the built-in Bluetooth proxy in ESPHome. This allows you to use ESPHome devices as proxies
for other Bluetooth devices such as BTHome sensors or a range of other Bluetooth devices.

> NOTE: Only beacons / devices broadcasting data are supported at the moment. Connectable devices will be supported in a
> future release.

> NOTE: The proxy bridge *CANNOT* be created in the UI, you *must* file based configuration!

The feature is still experimental and may not work as expected.

1. Configure the ESPHome device with the `bluetooth_proxy` component. See https://esphome.io/components/bluetooth_proxy

```yaml
bluetooth_proxy:
  active: true
```

2. Configure the ESPHome `device` in openHAB with `enableBluetoothProxy = true`

```yaml
esphome:device:garage-opener  "Garage ESP32" [ ... enableBluetoothProxy=true ]
```

3. Configure a Bluetooth Proxy bridge of type `esphome`

This is the standard configuration for any type of Bluetooth adapter in openHAB (not documented elsewhere)

| Name                             | Type      | Description                                                                 | Default | Required | Advanced |
|----------------------------------|-----------|-----------------------------------------------------------------------------|---------|----------|----------|
| `backgroundDiscovery`            | `boolean` | Add discovered device automatically to tihe inbox in the background         | false   | no       | no       |
| `inactiveDeviceCleanupInterval`  | `integer` | Number of seconds of Bluetooth device inactivity before removing from inbox | 60      | no       | no       |
| `inactiveDeviceCleanupThreshold` | `integer` |                                                                             | 300     | no       | no       |

```
Bridge bluetooth:esphome:proxy "ESPHome BLE Advertisement listener" [backgroundDiscovery = false] {
    bthome parasite1 "b-Parasite #4354" [address="XX:XX:XX:XX:18:91", expectedReportingIntervalSeconds = 600]
}
```

> **NOTE:** Set backgroundDiscovery to true if you want to automatically add discovered devices to the inbox. If not use
> manual scanning from the inbox.

## Streaming logs from ESPHome device to openHAB

As an alternative to manually streaming device logs via ESPHome dashboard, you can have openHAB stream
the device logs directly to openHAB - which will write them using the standard log system.

1. Make sure your ESPHome yaml is configured with a log level that produces the logs you want to see.
   See https://esphome.io/components/logger.html
2. Configure the `deviceLogLevel` parameter on the `thing` configuration. Valid
   values: https://esphome.io/components/logger.html#log-levels
3. The default log level in openHAB is `WARN`, so you need to add a logger named `ESPHOMEDEVICE`with level `INFO` to see
   actual log statements. Either add this to your `log4j.xml` file or use the Karaf console:

```
log:set INFO ESPHOMEDEVICE
```

**or**

```xml

<Loggers>
    ...
    <Logger level="DEBUG" name="ESPHOMEDEVICE"/>
</Loggers>
```

This will produce logs on level `INFO` in the openHAB logs like this:

```
[2024-04-04 15:06:25.822] [boiler] [D][dallas.sensor:143]: 'VV Temp bottom': Got Temperature=21.0°C
[2024-04-04 15:06:25.834] [boiler] [D][sensor:094]: 'VV Temp bottom': Sending state 21.00000 °C with 1 decimals of accuracy
[2024-04-04 15:06:25.850] [boiler] [D][dallas.sensor:143]: 'VV Temp middle': Got Temperature=71.7°C 
[2024-04-04 15:06:25.863] [boiler] [D][sensor:094]: 'VV Temp middle': Sending state 71.68750 °C with 1 decimals of accuracy
```

To redirect device logs to a separate log file, edit your `log4j.xml` file and add the following in the `<Appenders>`
section:

```xml

<RollingFile fileName="${sys:openhab.logdir}/esphomedevice.log"
             filePattern="${sys:openhab.logdir}/esphomedevice.log.%i" name="ESPHOMEDEVICE">
    <PatternLayout pattern="[%d{yyyy-MM-dd HH:mm:ss.SSS}] %m%n"/>
    <Policies>
        <SizeBasedTriggeringPolicy size="32 MB"/>
    </Policies>
</RollingFile>
```

And add the following in the `Loggers` section:

```xml

<Logger additivity="false" level="INFO" name="ESPHOMEDEVICE">
    <AppenderRef ref="ESPHOMEDEVICE"/>
</Logger>
```

## Sending state from openHAB to ESPHome

You can send state to the ESPHome device using the `homeassistant` sensor type. Only `entity_id` field is used.

You can listen for several types of OpenHAB events, default is `ItemStateChangedEvent`. The following are supported:

| entity_id                                  | OH Event listened for         | Item/Thing   |
|--------------------------------------------|-------------------------------|--------------|
| `<whatever>.ItemName`                      | `ItemStateChangedEvent`       | ItemName     | 
| `ItemStateChangedEvent.ItemName`           | `ItemStateChangedEvent`       | ItemName     | 
| `ItemStateEvent.ItemName`                  | `ItemStateEvent`              | ItemName     | 
| `ItemStateUpdatedEvent.ItemName`           | `ItemStateUpdatedEvent`       | ItemName     | 
| `ItemStatePredictedEvent.ItemName`         | `ItemStatePredictedEvent`     | ItemName     | 
| `GroupItemStateChangedEvent.ItemName`      | `GroupItemStateChangedEvent`  | ItemName     | 
| `GroupStateUpdatedEvent.ItemName`          | `GroupStateUpdatedEvent`      | ItemName     | 
| `ThingStatusInfoEvent.my_thing_uid`        | `ThingStatusInfoEvent`        | my:thing:uid | 
| `ThingStatusInfoChangedEvent.my_thing_uid` | `ThingStatusInfoChangedEvent` | my:thing:uid | 

> NOTE: EntityID in HA is case-insensitive - meaning only lowercase is used. Whatever you add in `entity_id` in the
> ESPHome yaml will be converted to lowercase.
> In OH item names are case-sensitive, so you can have 2 items like `MYITEM` and `MyItem`, and we cannot distinguish
> between the 2. Avoid this setup.

> NOTE2: In Thing UIDs, the `:` is replaced with `_`

> NOTE3: For Group events, it is the group state that is sent, not the individual item states.

### Examples

Making state changes to OH temperature sensor available in ESPHome:

```yaml
sensor:
  - platform: homeassistant
    name: "Outside temperature"
    entity_id: ItemStateChangedEvent.MyTemperatureItem
    device_class: temperature
```

Listening for commands sent from OH to some OH item and making it available in ESPHome:

```yaml
binary_sensor:
  - platform: homeassistant
    name: "Flower watering activating"
    entity_id: ItemCommandEvent.WaterValve_Switch
```

Making ESPHome device react when a Thing changes status, ie goes offline/online:

```yaml
text_sensor:
  - platform: homeassistant
    name: "ThingStatusInfoChangedEvent"
    entity_id: ThingStatusInfoChangedEvent.astro_moon_local
```

## Time sync from openHAB

Time sync from your openHAB server is supported using
the [HomeAssistant time source component](https://esphome.io/components/time/homeassistant).

```yaml
time:
  - platform: homeassistant
    id: openhab_time
```

## Iconify support

If you have
the [Iconify icon provider bundle installed](https://community.openhab.org/t/iconify-icon-provider-4-0-0-0-5-0-0-0/149990),
try configuring the `icon` field in the ESPHome yaml file. The binding will then use the icon from Iconify instead of
[ openHAB classic icons ](https://www.openhab.org/docs/configuration/iconsets/classic/).

  ```yaml
sensor:
  - platform: uptime
    name: Uptime
    icon: "mdi:counter"
```

## Limitations

Most entity types and functions are now supported. However, there are some limitations:

The following entity types are **not** yet supported (please submit a PR of file a feature request!)

- `camera`
- `voice`
- `valve`

- `light` - not all modes are supported. Please create a PR if you need a specific mode.

In addition, the Bluetooth proxy isn't fully ready yet.

