# ESPHome Native API Binding

![logo](logo.png)

This binding makes [ESPHome](https://esphome.io) devices available in openHAB through the ESPHome Home Assistant Native
API. This is an
alternative to using MQTT and/or running Home Assistant in addition to openHAB.

It does _NOT_ provide any webpage for managing the ESP themselves. Use
the [ESPHome dashboard](https://esphome.io/guides/installing_esphome.html) for that.

<img src="esphomedashboard.png" alt="ESPHome dashboard" width="30%"/>

Benefits of using the native API over MQTT:

- State descriptions on openHAB channels with value options
- No need for an MQTT broker
- Slightly faster than messaging over MQTT (according to the ESPHome documentation)

Read more here: https://esphome.io/components/api#advantages-over-mqtt

## Getting started for non ESPHome users

1. [Install ESPHome](https://esphome.io/guides/installing_esphome)
2. Create an ESPHome configuration for your device
3. Flash the device with the ESPHome firmware
4. Install the ESPHome binding in openHAB https://github.com/seime/openhab-esphome/releases/tag/latest_oh4
5. Wait for discovery to find your device - or add manually in a thing file.

> **Note:** At the current state of the binding, it is highly recommended to use file based configuration for things and
> items as channel types etc most likely will change.

## FAQ

- My ESPHome thing reports `COMMUNICATION_ERROR: Parse error`. What is wrong?

  > This is most likely because you have encryption set on your ESPHome device. The binding does not support encrypted
  > connections yet, so you need to disable encryption on your device.

- I get warnings
  like `No device_class reported by sensor '<name of sensor>'. Add device_class to sensor configuration in ESPHome. Defaulting to plain Number without dimension`

  > This is because the ESP sensor does not report a `device_class`. This field is used to determine item and category
  > type in openHAB.
  > Solution: Specify a `device_class` to your ESPHome configuration. Example: <br/>
  > ![img.png](esphomeconfig_deviceclass.png)
  > <br/>See https://developers.home-assistant.io/docs/core/entity/sensor/#available-device-classes for valid
  device_class values (**use lowercase values**)
  > Also note that you may override `device_class: ""` to remove any device class from the sensor.

Also see https://community.openhab.org/t/esphome-binding-for-the-native-api/146849/1 for more information.

## Supported Things

- `device`: A device flashed with https://esphome.io/ firmware.

## Limitations as of 2024-02-14

- **Only plaintext connections with password** are supported, not encrypted. This is insecure and should not be used on
  untrusted networks, but is your only option at this time. I *intend* to add encryption.
- Only
    - `sensor`,
    - `binary_sensor`,
    - `text_sensor`
    - `switch`,
    - `number`,
    - `button`,
    - `cover`,
    - `light` (will only show channels, but not control nor see the status of the light),
    - `climate` and
    - `select` is supported.
      Plans to add more, but not yet implemented. I need _your_ help.

## Discovery

The binding uses mDNS to automatically discover devices on the network.

## Thing Configuration

### `device` Thing Configuration

| Name              | Type      | Description                                                                    | Default | Required | Advanced |
|-------------------|-----------|--------------------------------------------------------------------------------|---------|----------|----------|
| `hostname`        | `text`    | Hostname or IP address of the device. Typically something like 'myboard.local' | N/A     | yes      | no       |
| `password`        | `text`    | Password to access the device if password protected                            | N/A     | no       | no       |
| `port`            | `integer` | IP Port of the device                                                          | 6053    | no       | no       |
| `pingInterval`    | `integer` | Seconds between sending ping requests to device to check if alive              | 10      | no       | yes      |
| `maxPingTimeouts` | `integer` | Number of missed ping requests before deeming device unresponsive.             | 4       | no       | yes      |

## Channels

Channels are auto-generated based on actual device configuration.

## Full Example

### Thing Configuration

```
esphome:device:esp1  "ESPHome Test card 1" [ hostname="testkort1.local", password="MyPassword", pingInterval=10, maxPingTimeouts=4]
```

### Item Configuration

```
Number:Temperature ESP1_Temperature "Temperature" <temperature>   {channel="esphome:device:esp1:temperature"}
Number:Dimensionless ESP1_Humidity "Humidity"     <humidity>      {channel="esphome:device:esp1:humidity"}
Switch ESP1_Switch "Relay"                        <switch>        {channel="esphome:device:esp1:relay_4"}
```
