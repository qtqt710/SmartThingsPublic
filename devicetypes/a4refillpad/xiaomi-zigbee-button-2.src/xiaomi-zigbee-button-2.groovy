/**
 *  Xiaomi Zigbee Button
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 * 
  * Based on original DH by Eric Maycock 2015 and Rave from Lazcad
 *  change log:
 *  added 100% battery max
 *  fixed battery parsing problem
 *  added lastcheckin attribute and tile
 *  added a means to also push button in as tile on smartthings app
 *  fixed ios tile label problem and battery bug 
 *
 */
metadata {
	definition (name: "Xiaomi Zigbee Button 2", namespace: "a4refillpad", author: "a4refillpad") {	
    	capability "Battery"
		capability "Button"
		capability "Holdable Button"
		capability "Actuator"
		capability "Switch"
		capability "Momentary"
		capability "Configuration"
		capability "Sensor"
		capability "Refresh"
        
		attribute "lastPress", "string"
		attribute "batterylevel", "string"
		attribute "lastCheckin", "string"
        
    	fingerprint profileId: "0104", deviceId: "0104", inClusters: "0000, 0003", outClusters: "0000, 0004, 0003, 0006, 0008, 0005", manufacturer: "LUMI", model: "lumi.sensor_switch", deviceJoinName: "Xiaomi Button"

	}
    
    simulator {
  	status "button 1 pressed": "on/off: 0"
      	status "button 1 released": "on/off: 1"
    }
    
    preferences{
    	input ("holdTime", "number", title: "Minimum time in miliseconds for a press to count as \"held\"",
        		defaultValue: 1800, displayDuringSetup: false)
    }

	tiles(scale: 2) {

		multiAttributeTile(name:"switch", type: "lighting", width: 6, height: 4, canChangeIcon: true) {
			tileAttribute ("device.switch", key: "PRIMARY_CONTROL") {
           		attributeState("on", label:' push', action: "momentary.push", backgroundColor:"#53a7c0")
            	attributeState("off", label:' push', action: "momentary.push", backgroundColor:"#ffffff", nextState: "on")   
 			}
            tileAttribute("device.lastCheckin", key: "SECONDARY_CONTROL") {
    			attributeState("default", label:'Last Update: ${currentValue}',icon: "st.Health & Wellness.health9")
            }
		}        
       
        valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
			state "battery", label:'${currentValue}% battery', unit:""
		}
        standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
        }
        standardTile("configure", "device.configure", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "configure", label:'', action:"configuration.configure", icon:"st.secondary.configure"
		}
        
		main (["switch"])
		details(["switch", "battery", "refresh", "configure"])
	}
}

/*def parse(String description) {
    log.debug "description is $description"

    def event = zigbee.getEvent(description)
    if (event) {
        sendEvent(event)
    }
    else {
        log.warn "DID NOT PARSE MESSAGE for description : $description"
        log.debug zigbee.parseDescriptionAsMap(description)
    }
}*/

def parse(String description) {
	//log.debug "description is $description"
  log.debug "Parsing '${description}'"
  //def value = zigbee.parse(description)?.text
  //log.debug "Parse: $value"
  
//  send event for heartbeat    
  def now = new Date().format("yyyy MMM dd EEE h:mm:ss a", location.timeZone)
  sendEvent(name: "lastCheckin", value: now)
  
  def results = []
  if (description?.startsWith('on/off: '))
		results = parseCustomMessage(description)
  if (description?.startsWith('catchall:')) 
		results = parseCatchAllMessage(description)
        
  return results;
}

def configure(){
    [
    "zdo bind 0x${device.deviceNetworkId} 1 2 0 {${device.zigbeeId}} {}", "delay 5000",
    
    // zcl global send-me-a-report [cluster] [attr] [type] [min-interval] [max-interval] [min-change]
    // "zcl global send-me-a-report 2 0 0x10 1 0 {01}", "delay 500",
    "zcl global send-me-a-report 2 0 0x10 600 1200 {01}", "delay 500",
    "send 0x${device.deviceNetworkId} 1 2"
    ]
}

def refresh(){
	"st rattr 0x${device.deviceNetworkId} 1 2 0"
    "st rattr 0x${device.deviceNetworkId} 1 0 0"
	log.debug "refreshing"
    
    createEvent([name: 'batterylevel', value: '100', data:[buttonNumber: 1], displayed: false])
    
    def current = now()
    state.firstPress = current - 6000
    state.secondPress = current - 3000
    state.lastPress = current
    log.debug "first          :         ${state.firstPress}"
    log.debug "second: ${state.secondPress}"
    // sendEvent([name: 'firstPress', value: current-60000, data:[buttonNumber: 1], displayed: false])
    // sendEvent([name: 'secondPress', value: current-3000, data:[buttonNumber: 1], displayed: false])
    // def first = device.latestState('firstPress')?.date?.getTime()
    // def second = device.latestState('secondPress')?.date?.getTime()
    // log.debug "current: ${current}"
    // log.debug "first          : ${first}"
    // log.debug "second: ${second}"
}

private Map parseCatchAllMessage(String description) {
	Map resultMap = [:]
	def cluster = zigbee.parse(description)
	log.debug cluster
	if (cluster) {
		switch(cluster.clusterId) {
			case 0x0000:
			resultMap = getBatteryResult(cluster.data.last())
			break

			case 0xFC02:
			log.debug 'ACCELERATION'
			break

			case 0x0402:
			log.debug 'TEMP'
				// temp is last 2 data values. reverse to swap endian
				String temp = cluster.data[-2..-1].reverse().collect { cluster.hex1(it) }.join()
				def value = getTemperature(temp)
				resultMap = getTemperatureResult(value)
				break
		}
	}

	return resultMap
}

private Map getBatteryResult(rawValue) {
	log.debug 'Battery'
	def linkText = getLinkText(device)

	log.debug rawValue
    
    int battValue = rawValue
     
    def maxbatt = 100

	if (battValue > maxbatt) {
				battValue = maxbatt
    }
   

	def result = [
		name: 'battery',
		value: battValue,
        unit: "%",
        isStateChange:true,
        descriptionText : "${linkText} battery was ${battValue}%"
	]
    
    log.debug result.descriptionText
    state.lastbatt = new Date().time
    return createEvent(result)
}

private Map parseCustomMessage(String description) {
	/*if (description?.startsWith('on/off: ')) {
    	if (description == 'on/off: 0') 		//button pressed
    		return createPressEvent(1)
    	else if (description == 'on/off: 1') 	//button released
    		return createButtonEvent(1)
	}
    */
    if (description?.startsWith('on/off: ')) {
    	def currentTime = now()
        //def firstPress = device.latestState('firstPress')?.date?.getTime()
        //def secondPress = device.latestState('secondPress')?.date?.getTime()
        //log.debug "firstPress: ${firstPress}"
        //log.debug "secondPress: ${secondPress}"
        
        //if (secondPress == null)
        //	secondPress = now()-3000
        def firstPress = state.firstPress
        def secondPress = state.secondPress
       	def timeDif1 = currentTime - firstPress
        def timeDif2 = currentTime - secondPress
        
        log.debug "time dif1: ${timeDif1}"
        log.debug "time dif2: ${timeDif2}"
        
        state.firstPress = secondPress
        state.secondPress = currentTime
        log.debug "firstPress: ${state.firstPress}"
        log.debug "secondPress: ${state.secondPress}"
        //sendEvent([name: 'firstPress', value: secondPress, data:[buttonNumber: 1], displayed: false])
        //sendEvent([name: 'secondPress', value: currentTime, data:[buttonNumber: 1], displayed: false])

        if (timeDif1<1200 && timeDif2<1200) {
        	log.debug "***********************************************doubleTap"
           // return createEvent(name: "button", value: "doubleTap", data: [buttonNumber: 1], descriptionText: "$device.displayName button $button was double tap", isStateChange: true)              
        	
        }
        	
        
        if (description == 'on/off: 0') {		//button pressed
        	def cur = now()
    		state.lastPress = cur
            log.debug "lastPress: ${state.lastPress}"
            //return createPressEvent(1)
            
        }
    	else if (description == 'on/off: 1') 	//button released
    		
            return createButtonEvent1(1)
        
	}
    
    
    
    
    /*if (description?.startsWith('on/off: ')) {
    	//createEvent([name: 'curPress', value: now(), data:[buttonNumber: button], displayed: false])
        def currentTime = now()
        def firstPress = device.latestState('firstPress')?.date?.getTime()
        log.debug "firstPress time: ${firstPress}"
        if (firstPress==null)
        	firstPress = now()-5000;
        def timeDif1 = currentTime - firstPress
        log.debug "TimeDif1: ${timeDif1}"
        
        def secondPress = device.latestState('secondPress')?.date?.getTime()
        if (secondPress==null)
        	secondPress = now()-4000;
        def timeDif2 = currentTime - secondPress
            
    	if (timeDif1<2000) {
        	if (timeDif2<2000)
       			return createEvent(name: "button", value: "doubleTap", data: [buttonNumber: 1], descriptionText: "$device.displayName button $button was double tap", isStateChange: true)
        } else {
        	  		
            if (timeDif2 > 2000) {
            	if (description == 'on/off: 0')
                	createEvent([name: 'firstPress', value: currentTime, data:[buttonNumber: 1], displayed: false])
            	else if (description == 'on/off: 1')
                	return createEvent(name: "button", value: "pushed", data: [buttonNumber: 1], descriptionText: "$device.displayName button $button was pushed", isStateChange: true)
            } else {
            	if (description == 'on/off: 0')
                	return createEvent(name: "button", value: "doubleTap", data: [buttonNumber: 1], descriptionText: "$device.displayName button $button was double tap", isStateChange: true)
            	else if (description == 'on/off: 1')
            		createEvent([name: 'secondPress', value: currentTime, data:[buttonNumber: 1], displayed: false])
            }
    	}
    }
    */
    
}

private createButtonEvent1(button) {
	def currentTime = now()
    //def startOfPress = device.latestState('lastPress').date.getTime()
    def timeDif = currentTime - state.lastPress
    //def holdTimeMillisec = (settings.holdTime?:3).toInteger() * 1000
    def holdTimeMillisec = settings.holdTime
    log.debug "Hold Time is: ${holdTimeMillisec}, Time dif is：   ${timeDif} "
    if (timeDif < 0) 
    	return []	//likely a message sequence issue. Drop this press and wait for another. Probably won't happen...
    else if (timeDif < 1200) { 
    	log.debug "#################################pushed"
        return createEvent(name: "button", value: "pushed", data: [buttonNumber: button], descriptionText: "$device.displayName button $button was pushed", isStateChange: true)
    
    }
    else if (timeDif > holdTimeMillisec && timeDif < 3500) { 
    	log.debug "@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@held"
        return createEvent(name: "button", value: "held", data: [buttonNumber: button], descriptionText: "$device.displayName button $button was held", isStateChange: true)

	}
}

//this method determines if a press should count as a push or a hold and returns the relevant event type
private createButtonEvent(button) {
	def currentTime = now()
    def startOfPress = device.latestState('lastPress').date.getTime()
    def timeDif = currentTime - startOfPress
    def holdTimeMillisec = (settings.holdTime?:3).toInteger() * 1000
    
    if (timeDif < 0) 
    	return []	//likely a message sequence issue. Drop this press and wait for another. Probably won't happen...
    else if (timeDif < holdTimeMillisec) { 
    	log.debug "#################################pushed"
        return createEvent(name: "button", value: "pushed", data: [buttonNumber: button], descriptionText: "$device.displayName button $button was pushed", isStateChange: true)
    
    }
    //else 
    	//return createEvent(name: "button", value: "held", data: [buttonNumber: button], descriptionText: "$device.displayName button $button was held", isStateChange: true)
}

private createPressEvent(button) {
	return createEvent([name: 'lastPress', value: now(), data:[buttonNumber: button], displayed: false])
}

//Need to reverse array of size 2
private byte[] reverseArray(byte[] array) {
    byte tmp;
    tmp = array[1];
    array[1] = array[0];
    array[0] = tmp;
    return array
}

private String swapEndianHex(String hex) {
    reverseArray(hex.decodeHex()).encodeHex()
}

def push() {
	sendEvent(name: "switch", value: "on", isStateChange: true, displayed: false)
	sendEvent(name: "switch", value: "off", isStateChange: true, displayed: false)
	sendEvent(name: "momentary", value: "pushed", isStateChange: true)
    sendEvent(name: "button", value: "pushed", data: [buttonNumber: 1], descriptionText: "$device.displayName button 1 was pushed", isStateChange: true)
}

def on() {
	push()
}

def off() {
	push()
}