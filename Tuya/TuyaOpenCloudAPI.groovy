/**
 *  MIT License
 *  Copyright 2020 Jonathan Bradshaw (jb@nrgup.net)
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
*/

import com.hubitat.app.exception.UnknownDeviceTypeException
import com.hubitat.app.ChildDeviceWrapper
import com.hubitat.app.DeviceWrapper
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.Random
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import hubitat.helper.HexUtils
import hubitat.helper.NetworkUtils
import hubitat.scheduling.AsyncResponse

/*
 *  Changelog:
 *  10/06/21 - 0.1   - Initial release
 *  10/08/21 - 0.1.1 - added Scene Switch TS004F productKey:xabckq1v
 *  10/09/21 - 0.1.2 - added Scene Switch TS0044 productKey:vp6clf9d; added battery reports (when the virtual driver supports it)
 *  10/10/21 - 0.1.3 - brightness, temperature, humidity, CO2 sensors
 *  10/11/21 - 0.1.4 - door contact, water, smoke, co, pir sensors, fan
 */

metadata {
    definition (name: 'Tuya IoT Platform (Cloud)', namespace: 'tuya', author: 'Jonathan Bradshaw') {
        capability 'Initialize'
        capability 'Refresh'

        command 'removeDevices'

        attribute 'deviceCount', 'Integer'
        attribute 'connected', 'Boolean'
    }

    preferences {
        section {
            input name: 'access_id',
                  type: 'text',
                  title: 'Tuya API Access/Client Id',
                  required: true

            input name: 'access_key',
                  type: 'password',
                  title: 'Tuya API Access/Client Secret',
                  required: true

            input name: 'appSchema',
                  title: 'Tuya Application',
                  type: 'enum',
                  required: true,
                  defaultValue: 'tuyaSmart',
                  options: [
                    'tuyaSmart': 'Tuya Smart Life App',
                    'smartlife': 'Smart Life App'
                ]

            input name: 'username',
                  type: 'text',
                  title: 'Tuya Application Login',
                  required: true

            input name: 'password',
                  type: 'password',
                  title: 'Tuya Application Password',
                  required: true

            input name: 'countryCode',
                  type: 'number',
                  title: 'Tuya Application Country Code',
                  required: true,
                  defaultValue: 1

            input name: 'logEnable',
                  type: 'bool',
                  title: 'Enable debug logging',
                  required: false,
                  defaultValue: true

            input name: 'txtEnable',
                  type: 'bool',
                  title: 'Enable descriptionText logging',
                  required: false,
                  defaultValue: true
        }
    }
}

// Tuya Function Categories
@Field static final Map<String, List<String>> tuyaFunctions = [
    'brightness'    : [ 'bright_value', 'bright_value_v2', 'bright_value_1' ],
    'colour'        : [ 'colour_data', 'colour_data_v2' ],
    'switch'        : [ 'switch', 'switch_led', 'switch_led_1', 'light' ],
    'temperature'   : [ 'temp_value', 'temp_value_v2' ],
    'workMode'      : [ 'work_mode' ],
    'sceneSwitch'   : [ 'switch1_value', 'switch2_value', 'switch3_value', 'switch4_value', 'switch_mode2', 'switch_mode3', 'switch_mode4' ],
    'omniSensor'    : [ 'bright_value', 'temp_current', 'humidity_value', 'va_humidity', 'bright_sensitivity' ],
    'battery'       : [ 'battery_percentage', 'va_battery' ],
    'contact'       : [ 'doorcontact_state' ],
    'water'         : [ 'watersensor_state' ],
    'smoke'         : [ 'smoke_sensor_status' ],
    'co'            : [ 'co_state' ],
    'co2'           : [ 'co2_value' ],
    'pir'           : [ 'pir' ],
    'fanSpeed'      : [ 'fan_speed' ]
]

// Tuya -> Hubitat attributes mappings
// TS004F  productKey:xabckq1v        TS0044 productKey:vp6clf9d
@Field static final Map<String, String> sceneSwitchAction = [
    'single_click'  : 'pushed',             // TS004F
    'double_click'  : 'doubleTapped',
    'long_press'    : 'held',
    'click'         : 'pushed',             // TS0044
    'double_click'  : 'doubleTapped',
    'press'         : 'held'
]
@Field static final Map<String, String> sceneSwitchKeyNumbers = [
    'switch_mode2'  : '2',                // TS0044
    'switch_mode3'  : '3',
    'switch_mode4'  : '4',
    'switch1_value' : '4',                // '4'for TS004F and '1' for TS0044 !
    'switch2_value' : '3',                // TS004F - match the key numbering as in Hubitat built-in TS0044 driver
    'switch3_value' : '1',
    'switch4_value' : '2',
]

// Constants
@Field static final Integer maxMireds = 500 // 2000K
@Field static final Integer minMireds = 153 // 6536K

// Jason Parsing Cache
@Field static final ConcurrentHashMap<String, Map> jsonCache = new ConcurrentHashMap<>()

// Track for dimming operations
@Field static final ConcurrentHashMap<String, Integer> levelChanges = new ConcurrentHashMap<>()

// Random number generator
@Field static final Random random = new Random()

/**
 *  Hubitat Driver Event Handlers
 */
// Component command to turn on device
void componentOn(DeviceWrapper dw) {
    Map functions = getFunctions(dw)
    String code = getFunctionByCode(functions, tuyaFunctions.switch)
    log.info "Turning ${dw} on"
    tuyaSendDeviceCommands(dw.getDataValue('id'), [ 'code': code, 'value': true ])
}

// Component command to turn off device
void componentOff(DeviceWrapper dw) {
    Map functions = getFunctions(dw)
    String code = getFunctionByCode(functions, tuyaFunctions.switch)
    log.info "Turning ${dw} off"
    tuyaSendDeviceCommands(dw.getDataValue('id'), [ 'code': code, 'value': false ])
}

// Component command to refresh device
void componentRefresh(DeviceWrapper dw) {
    String id = dw.getDataValue('id')
    if (id && dw.getDataValue('functions')) {
        log.info "Refreshing ${dw} (${id})"
        tuyaGetState(id)
    }
}

// Component command to set color
void componentSetColor(DeviceWrapper dw, Map colorMap) {
    Map functions = getFunctions(dw)
    String code = getFunctionByCode(functions, tuyaFunctions.colour)
    Map color = functions[code]
    // An oddity and workaround for mapping brightness values
    Map bright = functions['bright_value'] ?: functions['bright_value_v2'] ?: color.v
    Map value = [
        h: remap(colorMap.hue, 0, 100, color.h.min, color.h.max),
        s: remap(colorMap.saturation, 0, 100, color.s.min, color.s.max),
        v: remap(colorMap.level, 0, 100, bright.min, bright.max)
    ]
    log.info "Setting ${dw} color to ${colorMap}"
    tuyaSendDeviceCommands(dw.getDataValue('id'),
        [ 'code': code, 'value': value ],
        [ 'code': 'work_mode', 'value': 'colour']
    )
}

// Component command to set color temperature
void componentSetColorTemperature(DeviceWrapper dw, BigDecimal kelvin,
                                  BigDecimal level = null, BigDecimal duration = null) {
    Map functions = getFunctions(dw)
    String code = getFunctionByCode(functions, tuyaFunctions.temperature)
    Map temp = functions[code]
    Integer value = temp.max - Math.ceil(maxMireds - remap(1000000 / kelvin, minMireds, maxMireds, temp.min, temp.max))
    log.info "Setting ${dw} color temperature to ${kelvin}K"
    tuyaSendDeviceCommands(dw.getDataValue('id'),
        [ 'code': code, 'value': value ],
        [ 'code': 'work_mode', 'value': 'white']
    )
    if (level && dw.currentValue('level') != level) {
        componentSetLevel(dw, level, duration)
    }
}

// Component command to set effect
void componentSetEffect(DeviceWrapper dw, BigDecimal index) {
    log.warn "${device.displayName} Set effect command not supported"
}

// Component command to set hue
void componentSetHue(DeviceWrapper dw, BigDecimal hue) {
    componentSetColor(dw, [
        hue: hue,
        saturation: dw.currentValue('saturation') ?: 100,
        level: dw.currentValue('level') ?: 100
    ])
}

// Component command to set level
/* groovylint-disable-next-line UnusedMethodParameter */
void componentSetLevel(DeviceWrapper dw, BigDecimal level, BigDecimal duration = 0) {
    String colorMode = dw.currentValue('colorMode') ?: 'CT'
    if (colorMode == 'CT') {
        Map functions = getFunctions(dw)
        String code = getFunctionByCode(functions, tuyaFunctions.brightness)
        Map bright = functions[code] ?: [:]
        Integer value = Math.ceil(remap(level, 0, 100, bright.min ?: 0, bright.max ?: 100))
        log.info "Setting ${dw} level to ${level}%"
        tuyaSendDeviceCommands(dw.getDataValue('id'), [ 'code': code, 'value': value ])
    } else {
        componentSetColor(dw, [
            hue: dw.currentValue('hue') ?: 100,
            saturation: dw.currentValue('saturation') ?: 100,
            level: level
        ])
    }
}

void componentSetNextEffect(DeviceWrapper device) {
    log.warn "Set next effect command not supported"
}

void componentSetPreviousEffect(DeviceWrapper device) {
    log.warn "Set previous effect command not supported"
}

// Component command to set saturation
void componentSetSaturation(DeviceWrapper dw, BigDecimal saturation) {
    componentSetColor(dw, [
        hue: dw.currentValue('hue') ?: 100,
        saturation: saturation,
        level: dw.currentValue('level') ?: 100
    ])
}

// Component command to set fan speed
void componentSetSpeed(DeviceWrapper dw, String speed) {
    log.info "Setting speed to ${speed}"
    if (speed == 'on') {
        tuyaSendDeviceCommands(dw.getDataValue('id'), [ 'code': 'switch', 'value': true ])
    } else if (speed == 'off') {
        tuyaSendDeviceCommands(dw.getDataValue('id'), [ 'code': 'switch', 'value': false ])
    } else if (speed == 'auto') {
        log.warn 'Speed level auto is not supported'
    } else {
        Map functions = getFunctions(dw)
        String code = getFunctionByCode(functions, tuyaFunctions.fanSpeed)
        Map speedFunc = functions[code]
        int speedVal = ['low', 'medium-low', 'medium', 'medium-high', 'high'].indexOf(speed)
        String value
        switch (speedFunc.type) {
            case 'Enum':
                value = speedFunc.range[remap(speedVal, 0, 4, 1, speedFunc.range.size()) as int]
                break
            case 'Integer':
                value = remap(speedVal, 0, 4, speedFunc.min ?: 1, speedFunc.max ?: 100)
                break
            default:
                log.warn "Unknown fan speed function type ${speedFunc}"
                return
        }
        tuyaSendDeviceCommands(dw.getDataValue('id'), [ 'code': code, 'value': value ])
    }
}

// Component command to cycle fan speed
void componentCycleSpeed(DeviceWrapper dw) {
    switch(dw.currentValue('speed')) {
        case 'low': componentSetSpeed('medium-low'); break
        case 'medium-low': componentSetSpeed('medium'); break
        case 'medium': componentSetSpeed('medium-high'); break
        case 'medium-high': componentSetSpeed('high'); break
        case 'high': componentSetSpeed('low'); break
    }
}

// Component command to start level change (up or down)
void componentStartLevelChange(DeviceWrapper dw, String direction) {
    levelChanges[dw.deviceNetworkId] = (direction == 'down') ? -10 : 10
    log.info "Starting level change ${direction} for ${dw}"
    runInMillis(1000, 'doLevelChange')
}

// Component command to stop level change
void componentStopLevelChange(DeviceWrapper dw) {
    log.info "Stopping level change for ${dw}"
    levelChanges.remove(dw.deviceNetworkId)
}

// Utility function to handle multiple level changes
void doLevelChange() {
    List active = levelChanges.collect() // copy list locally
    active.each { kv ->
        ChildDeviceWrapper dw = getChildDevice(kv.key)
        if (dw) {
            int newLevel = (dw.currentValue('level') as int) + kv.value
            if (newLevel < 0) { newLevel = 0 }
            if (newLevel > 100) { newLevel = 100 }
            componentSetLevel(dw, newLevel)
            if (newLevel <= 0 && newLevel >= 100) {
                componentStopLevelChange(device)
            }
        } else {
            levelChanges.remove(kv.key)
        }
    }

    if (!levelChanges.isEmpty()) {
        runInMillis(1000, 'doLevelChange')
    }
}

// Called when the device is started
void initialize() {
    log.info "${device.displayName} driver initializing"
    state.clear()
    unschedule()

    state.endPoint = 'https://openapi.tuyaus.com' // default US endpoint
    state.tokenInfo = [ access_token: '', expire: now() ] // initialize token
    state.uuid = state?.uuid ?: UUID.randomUUID().toString()

    tuyaAuthenticate()
}

// Called when the device is first created
void installed() {
    log.info "${device.displayName} driver installed"
}

// Called when the device is removed
void uninstalled() {
    log.info "${device.displayName} driver uninstalled"
}

// Called when the settings are updated
void updated() {
    log.info "${device.displayName} driver configuration updated"
    if (logEnable) {
        log.debug settings
        runIn(1800, 'logsOff')
    }

    initialize()
}

// Called to parse received MQTT data
void parse(String data) {
    Map payload = new JsonSlurper().parseText(interfaces.mqtt.parseMessage(data).payload)
    Cipher cipher = Cipher.getInstance('AES/ECB/PKCS5Padding')
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(state.mqttInfo.password[8..23].bytes, 'AES'))
    Map result = new JsonSlurper().parse(cipher.doFinal(payload.data.decodeBase64()), 'UTF-8')
    if (result.status && (result.id || result.devId)) {
        updateDeviceStatus(result)
    } else if (result.bizCode && result.bizData) {
        parseBizData(result.bizCode, result.bizData)
    } else if (logEnable) {
        log.debug "${device.displayName} unsupported mqtt packet: ${result}"
    }
}

// Called to parse MQTT status changes
void mqttClientStatus(String status) {
    switch (status) {
        case 'Status: Connection succeeded':
            runInMillis(1000, 'tuyaHubSubscribe')
            break
        case 'Error: Connection lost':
        case 'Error: send error':
            log.error "${device.displayName} MQTT connection error: " + status
            sendEvent([ name: 'connected', value: false, descriptionText: 'connected is false'])
            runIn(15 + random.nextInt(45), 'tuyaGetHubConfig')
            break
    }
}

// Command to refresh all devices
void refresh() {
    log.info "${device.displayName} refreshing devices"
    tuyaGetDevices()
}

// Command to remove all the child devices
void removeDevices() {
    log.info "${device.displayName} removing all child devices"
    childDevices.each { device -> deleteChildDevice(device.deviceNetworkId) }
}

/**
 *  Driver Capabilities Implementation
 */
private static String translateColor(Integer hue, Integer saturation) {
    if (saturation < 1) {
        return 'White'
    }

    switch (hue * 3.6 as int) {
        case 0..15: return 'Red'
        case 16..45: return 'Orange'
        case 46..75: return 'Yellow'
        case 76..105: return 'Chartreuse'
        case 106..135: return 'Green'
        case 136..165: return 'Spring'
        case 166..195: return 'Cyan'
        case 196..225: return 'Azure'
        case 226..255: return 'Blue'
        case 256..285: return 'Violet'
        case 286..315: return 'Magenta'
        case 316..345: return 'Rose'
        case 346..360: return 'Red'
    }

    return ''
}

/**
  *  Tuya Standard Instruction Set Category Mapping to Hubitat Drivers
  *  https://developer.tuya.com/en/docs/iot/standarddescription?id=K9i5ql6waswzq
  */
private static Map mapTuyaCategory(String category) {
    switch (category) {
        case 'ykq':   // Remote Control
        case 'tyndj': // Solar Light
            return [ namespace: 'hubitat', name: 'Generic Component CT' ]
        case 'tgq':   // Dimmer Light
        case 'tgkg':  // Dimmer Switch
            return [ namespace: 'hubitat', name: 'Generic Component Dimmer' ]
        case 'fsd ':  // Ceiling Fan (with Light)
            return [ namespace: 'hubitat', name: 'Generic Component Fan Control' ]
        case 'dj':    // Light
        case 'dc':    // String Light
        case 'dd':    // Strip Light
        case 'gyd':   // Motion Sensor Lights
        case 'xdd':   // Ceiling Light
        case 'fwd':   // Ambient Light
        case 'tyndj': // Solar Light
            return [ namespace: 'hubitat', name: 'Generic Component RGBW' ]
        case 'wxkg':  // Scene switch (TS004F in 'Device trigger' mode only; TS0044)
            return [ namespace: 'hubitat', name: 'Generic Component Central Scene Switch' ]
        case 'ldcg':  // Brightness, temperature, humidity, CO2 sensors
        case 'wsdcg':
            return [ namespace: 'hubitat', name: 'Generic Component Omni Sensor' ]
        case 'mcs':   // Contact Sensor
            return [ namespace: 'hubitat', name: 'Generic Component Contact Sensor' ]
        case 'sj':    // Water Sensor
            return [ namespace: 'hubitat', name: 'Generic Component Water Sensor' ]
        case 'ywbj':  // Smoke Detector
            return [ namespace: 'hubitat', name: 'Generic Component Smoke Detector' ]
        case 'cobj':  // CO Detector
            return [ namespace: 'hubitat', name: 'Generic Component Carbon Monoxide Detector' ]
        case 'co2bj': // CO2 Sensor
            return [ namespace: 'hubitat', name: 'Generic Component Carbon Dioxide Detector' ]
        case 'pir':   // Motion Sensor
            return [ namespace: 'hubitat', name: 'Generic Component Motion Sensor' ]
        default:
            return [ namespace: 'hubitat', name: 'Generic Component Switch' ]
    }
}

private static Map getFunctions(DeviceWrapper dw) {
    return jsonCache.computeIfAbsent(dw.getDataValue('functions')) {
        k -> new JsonSlurper().parseText(k)
    }
}

private static String getFunctionByCode(Map functions, List codes) {
    return codes.find { c -> functions.containsKey(c) } ?: codes.first()
    // TODO: Include default function values
}

private static BigDecimal remap(BigDecimal oldValue, BigDecimal oldMin, BigDecimal oldMax,
                                BigDecimal newMin, BigDecimal newMax) {
    BigDecimal value = oldValue
    if (value < oldMin) { value = oldMin }
    if (value > oldMax) { value = oldMax }
    BigDecimal newValue = ( (value - oldMin) / (oldMax - oldMin) ) * (newMax - newMin) + newMin
    return newValue.setScale(1, BigDecimal.ROUND_HALF_UP)
}

private static Integer remap(Integer oldValue, Integer oldMin, Integer oldMax,
                             Integer newMin, Integer newMax) {
    return remap(oldValue, oldMin, oldMax, newMin, newMax) as Integer
}

private ChildDeviceWrapper createChildDevice(Map d) {
    ChildDeviceWrapper dw = getChildDevice("${device.id}-${d.id}")
    if (!dw) {
        Map driver = mapTuyaCategory(d.category)
        log.info "${device.displayName} creating device ${d.name} using ${driver.name} driver"
        try {
            dw = addChildDevice(driver.namespace, driver.name, "${device.id}-${d.id}",
                [
                    name: d.product_name,
                    label: d.name,
                    //location: add room support if we can get from Tuya
                ]
            )
        } catch (UnknownDeviceTypeException e) {
            log.warn "${device.displayName} ${e.message} - you may need to install the driver"
            return null
        }
    }

    dw.with {
        label = label ?: d.name
        updateDataValue 'id', d.id
        updateDataValue 'local_key', d.local_key
        updateDataValue 'category', d.category
        updateDataValue 'online', d.online as String
    }

    return childDevice
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void logsOff() {
    device.updateSetting('logEnable', [value: 'false', type: 'bool'] )
    log.info "${device.displayName} debug logging disabled"
}

private void parseBizData(String bizCode, Map bizData) {
    String indicator = ' [Offline]'
    ChildDeviceWrapper dw = bizData.devId ? getChildDevice("${device.id}-${bizData.devId}") : null

    if (logEnable) { log.debug "${device.displayName} ${bizCode} ${bizData}" }
    switch (bizCode) {
        case 'nameUpdate':
            dw.label = bizData.name
            break
        case 'online':
            dw.updateDataValue('online', 'true')
            if (dw.label.endsWith(indicator)) {
                dw.label -= indicator
            }
            break
        case 'offline':
            dw.updateDataValue('online', 'false')
            if (!dw.label.endsWith(indicator)) {
                dw.label += indicator
            }
            break
        case 'bindUser':
            refresh()
            break
    }
}

private void updateDeviceStatus(Map d) {
    ChildDeviceWrapper dw = getChildDevice("${device.id}-${d.id ?: d.devId}")
    if (!dw) {
        log.error "${device.displayName} updateDeviceStatus: Child device for ${d} not found"
        return
    }

    Map deviceFunctions = getFunctions(dw)
    List<Map> statusList = d.status ?: []
    String workMode = statusList['workMode'] ?: ''
    List<Map> events = statusList.collectMany { status ->
        if (logEnable) { log.debug "${device.displayName} ${dw.displayName} status ${status}" }

        if (status.code in tuyaFunctions.brightness && workMode != 'colour') {
            Map bright = deviceFunctions[status.code]
            if ( bright != null )
            {
                Integer value = Math.floor(remap(status.value, bright.min, bright.max, 0, 100))
                if (txtEnable) { log.info "${dw.displayName} level is ${value}%" }
                return [ [ name: 'level', value: value, unit: '%', descriptionText: "level is ${value}%" ] ]
            }
        }

        if (status.code in tuyaFunctions.colour) {
            Map colour = deviceFunctions[status.code]
            Map bright = deviceFunctions[getFunctionByCode(deviceFunctions, tuyaFunctions.brightness)] ?: colour.v
            Map value = status.value == '' ? [h: 100.0, s: 100.0, v: 100.0] :
                        jsonCache.computeIfAbsent(status.value) { k -> new JsonSlurper().parseText(k) }
            Integer hue = Math.floor(remap(value.h, colour.h.min, colour.h.max, 0, 100))
            Integer saturation = Math.floor(remap(value.s, colour.s.min, colour.s.max, 0, 100))
            Integer level = Math.floor(remap(value.v, bright.min, bright.max, 0, 100))
            String colorName = translateColor(hue, saturation)
            if (txtEnable) { log.info "${dw.displayName} color is h:${hue} s:${saturation} (${colorName})" }
            List<Map> events = [
                [ name: 'hue', value: hue, descriptionText: "hue is ${hue}" ],
                [ name: 'saturation', value: saturation, descriptionText: "saturation is ${saturation}" ],
                [ name: 'colorName', value: colorName, descriptionText: "color name is ${colorName}" ]
            ]
            if (workMode == 'color') {
                if (txtEnable) { log.info "${dw.displayName} level is ${level}%" }
                events << [ name: 'level', value: level, unit: '%', descriptionText: "level is ${level}%" ]
            }
            return events
        }

        if (status.code in tuyaFunctions.fanSpeed) {
            Map speed = deviceFunctions[status.code]
            int value
            switch (speed.type) {
                case 'Enum':
                    value = remap(status.value as int, speed.range.min(), speed.range.max(), 0, 4) as int
                    break
                case 'Integer':
                    value = remap(status.value as int, speed.min ?: 1, speed.max ?: 100, 0, 4) as int
                    break
            }
            String level = ['low', 'medium-low', 'medium', 'medium-high', 'high'].get(value)
            if (txtEnable) { log.info "${dw.displayName} speed is ${level}" }
            events << [ name: 'speed', value: level, descriptionText: "speed is ${level}" ]
        }

        if (status.code in tuyaFunctions.contact) {
            String value = status.value ? 'open' : 'closed'
            if (txtEnable) { log.info "${dw.displayName} contact is ${value}" }
            return [ [ name: 'contact', value: value, descriptionText: "contact is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.water) {
            String value = status.value == 'alarm' ? 'wet' : 'dry'
            if (txtEnable) { log.info "${dw.displayName} water is ${value}" }
            return [ [ name: 'water', value: value, descriptionText: "water is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.smoke) {
            String value = status.value == 'alarm' ? 'detected' : 'clear'
            if (txtEnable) { log.info "${dw.displayName} smoke is ${value}" }
            return [ [ name: 'smoke', value: value, descriptionText: "smoke is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.co) {
            String value = status.value == 'alarm' ? 'detected' : 'clear'
            if (txtEnable) { log.info "${dw.displayName} carbon monoxide is ${value}" }
            return [ [ name: 'carbonMonoxide', value: value, descriptionText: "carbon monoxide is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.co2) {
            String value = status.value
            if (txtEnable) { log.info "${dw.displayName} carbon dioxide level is ${value}" }
            return [ [ name: 'carbonDioxide', value: value, unit: 'ppm', descriptionText: "carbon dioxide level is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.pir) {
            String value = status.value == 'pir' ? 'active' : 'inactive'
            if (txtEnable) { log.info "${dw.displayName} motion is ${value}" }
            return [ [ name: 'motion', value: value, descriptionText: "motion is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.switch) {
            String value = status.value ? 'on' : 'off'
            if (txtEnable) { log.info "${dw.displayName} switch is ${value}" }
            return [ [ name: 'switch', value: value, descriptionText: "switch is ${value}" ] ]
        }

        if (status.code in tuyaFunctions.temperature) {
            Map temperature = deviceFunctions[status.code]
            Integer value = Math.floor(1000000 / remap(temperature.max - status.value,
                            temperature.min, temperature.max, minMireds, maxMireds))
            if (txtEnable) { log.info "${dw.displayName} color temperature is ${value}K" }
            return [ [ name: 'colorTemperature', value: value, unit: 'K',
                       descriptionText: "color temperature is ${value}K" ] ]
        }

        if (status.code in tuyaFunctions.sceneSwitch) {
            String action
            if (status.value in sceneSwitchAction) {
                 action = sceneSwitchAction[status.value]
            } else {
                log.warn "${dw.displayName} sceneSwitch: unknown status.value ${status.value}"
            }

            String value
            if (status.code in sceneSwitchKeyNumbers) {
                value = sceneSwitchKeyNumbers[status.code]
                if (d.productKey == 'vp6clf9d' && status.code == 'switch1_value') {
                   value = '1'                    // correction for TS0044 key #1
                }
            } else {
                log.warn "${dw.displayName} sceneSwitch: unknown status.code ${status.code}"
            }

            if (value != null && action != null) {
                if (txtEnable) { log.info "${dw.displayName} buttons ${value} is ${action}" }
                return [ [ name: action, value: value, descriptionText: "button ${value} is ${action}", isStateChange: true ] ]
            } else {
                log.warn "${dw.displayName} sceneSwitch: unknown name ${action} or value ${value}"
            }
        }

        if (status.code in tuyaFunctions.battery) {
            if (status.code == 'battery_percentage' || status.code == 'va_battery') {
                if (txtEnable) { log.info "${dw.displayName} battery is ${status.value}%" }
                return [ [ name: 'battery', value: status.value, descriptionText: "battery is ${status.value}%", unit: "%" ] ]
            }
        }

        if (status.code in tuyaFunctions.omniSensor) {
            String name
            String value
            String unit
            switch (status.code) {
                case 'bright_value':
                    name = 'illuminance'
                    value = status.value
                    unit = 'Lux'
                    break
                case 'temp_current':
                case 'va_temperature':
                    value = status.value
                    if (status.code == 'temp_current') {
                        value = value / 10
                    }
                    name = 'temperature'
                    unit = "\u00B0${location.temperatureScale}"
                    break
                case 'humidity_value':
                case 'va_humidity':
                    name = 'humidity'
                    value = status.value / 10
                    unit = 'RH%'
                    break
                case 'bright_sensitivity':
                    name = 'sensitivity'
                    value = status.value
                    unit = '%'
                    break
                default:
                    log.warn "${dw.displayName} unsupported omniSensor status.code ${status.code}"
            }
            if (name != null && value != null) {
                if (txtEnable) { log.info "${dw.displayName} ${name} is ${value} ${unit}" }
                return [ [ name: name, value: value, descriptionText: "${name} is ${value} ${unit}", unit: unit ] ]
            }
        }

        if (status.code in tuyaFunctions.workMode) {
            switch (status.value) {
                case 'white':
                case 'light_white':
                    if (txtEnable) { log.info "${dw.displayName} color mode is CT" }
                    return [ [ name: 'colorMode', value: 'CT', descriptionText: "color mode is CT" ] ]
                case 'colour':
                    if (txtEnable) { log.info "${dw.displayName} color mode is RGB" }
                    return [ [ name: 'colorMode', value: 'RGB', descriptionText: "color mode is RGB" ] ]
            }
        }

        return []
    }

    if (events && logEnable) { log.debug "${device.displayName} ${dw.displayName} sending events ${events}" }
    dw.parse(events)
}

/**
 *  Tuya Open API Authentication
 *  https://developer.tuya.com/en/docs/cloud/
*/
private void tuyaAuthenticate() {
    if (settings.username && settings.password && settings.appSchema && settings.countryCode) {
        log.info "${device.displayName} starting Tuya cloud authentication for ${settings.username}"
        MessageDigest digest = MessageDigest.getInstance('MD5')
        String md5pwd = HexUtils.byteArrayToHexString(digest.digest(settings.password.bytes)).toLowerCase()
        Map body = [
            'country_code': settings.countryCode,
            'username': settings.username,
            'password': md5pwd,
            'schema': settings.appSchema
        ]
        state.tokenInfo.access_token = ''
        tuyaPost('/v1.0/iot-01/associated-users/actions/authorized-login', body, 'tuyaAuthenticateResponse')
    } else {
        log.error "${device.displayName} must be configured before authentication is possible"
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaAuthenticateResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) {
        sendEvent([ name: 'connected', value: false, descriptionText: 'connected is false'])
        return
    }

    Map result = response.json.result
    state.endPoint = result.platform_url
    state.tokenInfo = [
        access_token: result.access_token,
        refresh_token: result.refresh_token,
        uid: result.uid,
        expire: result.expire_time * 1000 + now(),
    ]
    log.info "${device.displayName} received Tuya access token (valid for ${result.expire_time}s)"

    // Schedule next authentication
    runIn(result.expire_time - 60, 'tuyaAuthenticate')

    // Get MQTT details
    tuyaGetHubConfig()
}

/**
 *  Tuya Open API Device Management
 *  https://developer.tuya.com/en/docs/cloud/
 */
private void tuyaGetDevices() {
    if (!jsonCache.isEmpty()) {
        log.info "${device.displayName} clearing json cache"
        jsonCache.clear()
    }

    log.info "${device.displayName} requesting cloud devices (maximum 100)"
    tuyaGet('/v1.0/iot-01/associated-users/devices', [ 'size': 100 ], 'tuyaGetDevicesResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetDevicesResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }
    Map result = response.json.result
    data.devices = result.devices
    log.info "${device.displayName} received ${result.devices.size()} cloud devices"
    sendEvent([ name: 'deviceCount', value: result.devices.size() as String ])

    // Create Hubitat devices from Tuya results
    result.devices.each { d ->
        createChildDevice(d)
    }

    // Get device functions in batches of 20
    result.devices.collate(20).each { collection ->
        tuyaGetDeviceFunctions(collection, data)
        pauseExecution(1000)
    }
}

private void tuyaGetDeviceFunctions(List<Map> devices, Map data = [:]) {
    log.info "${device.displayName} requesting cloud device functions for ${devices*.name}"
    tuyaGet('/v1.0/devices/functions', [ 'device_ids': devices*.id.join(',') ], 'tuyaGetDeviceFunctionsResponse', data)
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetDeviceFunctionsResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }
    JsonSlurper parser = new JsonSlurper()
    List result = response.json.result
    log.info "${device.displayName} received ${result.size()} cloud function groups"
    result?.each { group ->
        Map functions = group.functions.collectEntries { f ->
            String code = f.code
            Map values = parser.parseText(f.values)
            values.type = f.type
            [ (code): values ]
        }
        String json = JsonOutput.toJson(functions)
        group.devices.each { id ->
            ChildDeviceWrapper dw = getChildDevice("${device.id}-${id}")
            dw?.updateDataValue('functions', json)
        }
    }

    // Process status updates
    data.devices.each { d -> updateDeviceStatus(d) }
    sendEvent([ name: 'connected', value: true, descriptionText: 'connected is true'])
}

private void tuyaGetState(String deviceID) {
    if (logEnable) { log.debug "${device.displayName} requesting device ${deviceID} state" }
    tuyaGet("/v1.0/devices/${deviceID}/status", null, 'tuyaGetStateResponse', [ id: deviceID ])
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetStateResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }
    data.status = response.json.result
    updateDeviceStatus(data)
}

private void tuyaSendDeviceCommands(String deviceID, Map...params) {
    if (logEnable) { log.debug "${device.displayName} device ${deviceID} command ${params}" }
    if (!state?.tokenInfo?.access_token) {
        log.error "${device.displayName} tuyaSendDeviceCommands Error - Access token is null"
        return
    }
    tuyaPost("/v1.0/devices/${deviceID}/commands", [ 'commands': params ], 'tuyaSendDeviceCommandsResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaSendDeviceCommandsResponse(AsyncResponse response, Map data) {
    tuyaCheckResponse(response)
}

/**
 *  Tuya Open API MQTT Hub
 *  https://developer.tuya.com/en/docs/cloud/
 */
private void tuyaGetHubConfig() {
    log.info "${device.displayName} requesting Tuya MQTT configuration"
    Map body = [
        'uid': state.tokenInfo.uid,
        'link_id': state.uuid,
        'link_type': 'mqtt',
        'topics': 'device',
        'msg_encrypted_version': '1.0'
    ]

    tuyaPost('/v1.0/iot-03/open-hub/access-config', body, 'tuyaGetHubConfigResponse')
}

/* groovylint-disable-next-line UnusedPrivateMethod, UnusedPrivateMethodParameter */
private void tuyaGetHubConfigResponse(AsyncResponse response, Map data) {
    if (!tuyaCheckResponse(response)) { return }
    Map result = response.json.result
    state.mqttInfo = result
    tuyaHubConnect()
}

private void tuyaHubConnect() {
    log.info "${device.displayName} connecting to Tuya MQTT hub at ${state.mqttInfo.url}"
    try {
        interfaces.mqtt.connect(
            state.mqttInfo.url,
            state.mqttInfo.client_id,
            state.mqttInfo.username,
            state.mqttInfo.password)
    } catch (e) {
        log.error "${device.displayName} MQTT connection error: " + e
        runIn(30, 'tuyaGetHubConfig')
    }
}

/* groovylint-disable-next-line UnusedPrivateMethod */
private void tuyaHubSubscribe() {
    state.mqttInfo.source_topic.each { t ->
        log.info "${device.displayName} subscribing to Tuya MQTT hub ${t.key} topic"
        interfaces.mqtt.subscribe(t.value)
    }

    tuyaGetDevices()
}

/**
 *  Tuya Open API HTTP REST Implementation
 *  https://developer.tuya.com/en/docs/cloud/
 */
private void tuyaGet(String path, Map query, String callback, Map data = [:]) {
    tuyaRequest('get', path, callback, query, null, data)
}

private void tuyaPost(String path, Map body, String callback, Map data = [:]) {
    tuyaRequest('post', path, callback, null, body, data)
}

/* groovylint-disable-next-line ParameterCount */
private void tuyaRequest(String method, String path, String callback, Map query, Map body, Map data) {
    String accessToken = state?.tokenInfo?.access_token ?: ''
    String stringToSign = tuyaGetStringToSign(method, path, query, body)
    long now = now()
    Map headers = [
      't': now,
      'client_id': access_id,
      'Signature-Headers': 'client_id',
      'sign': tuyaGetSignature(accessToken, now, stringToSign),
      'sign_method': 'HMAC-SHA256',
      'access_token': accessToken,
      'lang': 'en'
    ]

    Map request = [
        uri: state.endPoint,
        path: path,
        query: query,
        contentType: 'application/json',
        headers: headers,
        body: JsonOutput.toJson(body),
        timeout: 5
    ]

    if (logEnable) {
        log.debug("${device.displayName} API ${method.toUpperCase()} ${request}")
    }

    switch (method) {
        case 'get': asynchttpGet(callback, request, data); break
        case 'post': asynchttpPost(callback, request, data); break
    }
}

private boolean tuyaCheckResponse(AsyncResponse response) {
    if (response.status != 200) {
        log.error "${device.displayName} cloud request returned HTTP status ${response.status}"
        return false
    }

    if (response.json?.success != true) {
        log.warn "${device.displayName} cloud request failed: ${response.data}"
        return false
    }

    if (logEnable) {
        log.debug "${device.displayName} API response ${response.json ?: response.data}"
    }

    return true
}

private String tuyaGetSignature(String accessToken, long timestamp, String stringToSign) {
    String message = access_id + accessToken + timestamp.toString() + stringToSign
    Mac sha256HMAC = Mac.getInstance('HmacSHA256')
    sha256HMAC.init(new SecretKeySpec(access_key.bytes, 'HmacSHA256'))
    return HexUtils.byteArrayToHexString(sha256HMAC.doFinal(message.bytes))
}

private String tuyaGetStringToSign(String method, String path, Map query, Map body) {
    String url = query ? path + '?' + query.collect { key, value -> "${key}=${value}" }.join('&') : path
    String headers = 'client_id:' + access_id + '\n'
    String bodyStream = (body == null) ? '' : JsonOutput.toJson(body)
    MessageDigest sha256 = MessageDigest.getInstance('SHA-256')
    String contentSHA256 = HexUtils.byteArrayToHexString(sha256.digest(bodyStream.bytes)).toLowerCase()
    return method.toUpperCase() + '\n' + contentSHA256 + '\n' + headers + '\n' + url
}
