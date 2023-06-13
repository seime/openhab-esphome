# ESPHome Native API Binding

![logo](logo.png)

This binding makes [ESPHome](https://esphome.io) devices available in openHAB through the ESPHome Home Assistant Native
API. This is an
alternative to using MQTT and/or running Home Assistant in addition to openHAB.

It does _NOT_ provide any webpage for managing the ESP themselves. Use
the [ESPHome dashboard](https://esphome.io/guides/installing_esphome.html) for that.

<img src="esphomedashboard.png" alt="ESPHome dashboard" width="30%"/>

Benefits of using the native API:

- No need for an MQTT broker
- Slightly faster than messaging over MQTT (according to the ESPHome documentation)
- State descriptions on openHAB channels with value options

## Getting started for non ESPHome users

1. Install ESPHome
2. Create an ESPHome configuration for your device
3. Flash the device with the ESPHome firmware
4. Install the ESPHome binding in openHAB
5. Wait for discovery to find your device - or add manually in a thing file.

## Supported Things

- `device`: A device flashed with https://esphome.io/ firmware.

## Limitations as of 2023-06-13

- Only plaintext connections are supported, not encrypted. This is insecure and should not be used on untrusted
  networks.
- Only `sensor`, `binary sensor` `switch`, `climate` and `select` is supported. Plans to add more, but not yet
  implemented. I need _your_
  help.

## Discovery

The binding uses mDNS to automatically discover devices on the network.

## Thing Configuration

### `device` Thing Configuration

| Name     | Type    | Description                                                                    | Default | Required | Advanced |
|----------|---------|--------------------------------------------------------------------------------|---------|----------|----------|
| hostname | text    | Hostname or IP address of the device. Typically something like 'myboard.local' | N/A     | yes      | no       |
| password | text    | Password to access the device if password protected                            | N/A     | no       | no       |
| port     | integer | IP Port of the device                                                          | 6053    | no       | no       |

## Channels

Channels are auto-generated based on the device configuration.

Some possible channel _types_ are:

| Channel Typep | Type                 | Read/Write | Description                                       |
|---------------|----------------------|------------|---------------------------------------------------|
| temperature   | Number:Temperature   | R          | Sensor reported temperature                       |
| humidity      | Number:Dimensionless | R          | Sensor reported relative humidity                 |
| number        | Number               | R          | For all other yet to be supported types of values |
| switch        | Switch               | RW         | Switches and relays                               |
| contact       | Switch               | RW         | Binary sensors                                    |

_Actual_ channel name is based on the ESPHome configuration.

## Full Example

### Thing Configuration

```
esphome:device:esp1  "ESPHome Test card 1" [ hostname="testkort1.local", password="MyPassword" ]
```

### Item Configuration

```
Number:Temperature ESP1_Temperature "Temperature [%.1f %unit%]" <temperature>   {channel="esphome:device:esp1:temperature"}
Number:Dimensionless ESP1_Humidity "Humidity [%.1f %unit%]"     <humidity>      {channel="esphome:device:esp1:humidity"}
Switch ESP1_Switch "Relay"                                      <switch>        {channel="esphome:device:esp1:relay_4"}
```
