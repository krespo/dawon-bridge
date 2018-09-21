/**
 *  Dawon Bridge
 *
 *  Copyright 2018 krespo
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
 */
definition(
    name: "Dawon Bridge",
    namespace: "krespo",
    author: "krespo",
    description: "Dawon Smart Plug SmartApp",
    category: "My Apps",
    iconUrl: "https://github.com/krespo/dawon-bridge/raw/master/images/dawon_bridge.png",
    iconX2Url: "https://github.com/krespo/dawon-bridge/raw/master/images/dawon_bridge.png",
    iconX3Url: "https://github.com/krespo/dawon-bridge/raw/master/images/dawon_bridge.png")


preferences {

	section("Login Information") {

		    input "emailId", "text", required: true, title: "Google EMail Address"
        input "deviceId", "text", required: true, title: "Device ID"
        input "mainSwitch", "capability.switch", required: true, title: "Switch"
	}


}



def installed() {

	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

	unsubscribe()
	initialize()
}

def initialize() {

	state.loginSessionId = ""

	subscribe(mainSwitch, "switch", switchHandler)

    tryLogin()
}

def switchHandler(evt) {
	sendDeviceAction(evt.value)
}

// TODO: implement event handlers

//api request
def getAPIPrefix() {
	return "https://dwapi.dawonai.com:18443"
}

def tryLogin() {
	log.debug ">>>>>>>> try Login"

	def params = [
    	uri: getAPIPrefix()
        , path: "/iot/member/loginAction.opi"
        , headers: [
        	'X-Requested-With': 'XMLHttpRequest',
            'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8',
            'Cookie': 'JSESSIONID=' + getSessionId(false)
        ]
        , query: [
			'user_id': emailId + "/google",
            'sso_token':"unuseddata",
            'fcm_token':"unuseddata",
            'terminal_id':"unuseddata",
            'os_type':'Android',
            'email':"unuseddata",
            'name':"unuseddata",
            'register':'google',
            'terminal_name':'SAMSUNG'
        ]
    ]
    try {

        httpPost(params) { resp ->
            String responseData = resp.data.getText();
            //정상로그인 이면, 세션 아이디를 저장
            if("Y".equals(responseData)) {
                log.debug "[[[[[[ Login Success ]]]]]]"
                state.loginSessionId = findSessionId(resp.headers);

                log.debug "sessionId : ${state.loginSessionId}"

                unschedule()
                runEvery1Minute(checkDeviceState)
            }
            else {
                log.error "Login Failed...."
            }
        }
	}catch(e) {
    	state.loginSessionId = "";
    	log.error "login failed"
    }
}

def checkDeviceState() {

	def params = [
    	uri: getAPIPrefix()
        , path: "/iot/product/device_list.opi"
 		, headers: [
        	'Cookie': 'JSESSIONID=' + state.loginSessionId,
            'Content-Type':'application/json; charset=UTF-8'
        ]
    ]
    try {
    	httpGet(params) { resp ->
            if(resp.getStatus() == 200) {
            	log.info "Success Device Checking"
				String deviceStatus = resp.data?.getText()

                if(deviceStatus) {
                	def jsonResult = new groovy.json.JsonSlurper().parseText(deviceStatus)
                    def findDevice = jsonResult.devices?.find  { it.device_id == deviceId}

                    if(findDevice == null) {
                    	log.error "입력한 디바이스 아이디를 api결과에서 찾을 수 없습니다. device 아이디를 다시 확인해주세요"
                    }
                    else {
                    	boolean deviceState = findDevice.device_profile?.power == "true"

                        log.info "current Device State: ${deviceState}"

                        if(deviceState == true) {
                            log.debug "switch On"
                            mainSwitch.on()
                        }
                        else  {
                            log.debug "switch Off"
                            mainSwitch.off()
                        }
                    }

                }

            }
            else {
	            log.error "디바이스 상태를 가져오는데 에러가 발생했습니다"
            }
    	}
   	} catch(e) {
    	//앱에서 로그인을 하면 로그인이 풀려버리는듯... 에러 발생시 다시한번 로그인한다.
    	log.error "디바이스 상태를 가져오는데 실패했습니다. $e"
        tryLogin()
    }

}

def getSessionId(boolean force) {
	if(force || state.sessionId == "") {
    	def params = [
            uri: getAPIPrefix()
            , path: "/iot/"
        ]
        try {
            httpGet(params) { resp ->
                return findSessionId(resp.headers)
            }
        }
        catch(e) {
            log.error "something went wrong: $e"
        }
    }
}

/*
* 헤더의 Set-Cookie 부분에서 JSESSIONID 를 추출한다.
*
*/
def findSessionId(headers) {
	return headers.getAt('Set-Cookie').value.split(";", -2)
                                    .find {it.trim().startsWith("JSESSIONID=")}
                                    .replaceAll("JSESSIONID=", "")
}

def sendDeviceAction(action) {

    for(int retryCount = 0; retryCount < 2; retryCount ++) {
    	try {

            log.debug "send request device action"
            requestApiCallForDeviceAction(action)
            break;

        }
        catch(e) {
        	log.error "[${retryCouht + 1} 번 요청]디바이스 상태를 ${action}으로 변경하는데 실패했습니다 ==> ${e}"
            tryLogin()
        }

    }
}

def requestApiCallForDeviceAction(action) {
	def params = [
    	uri: getAPIPrefix()
        , path: "/iot/product/device_${action}.opi"
 		, headers: [
        	'X-Requested-With': 'XMLHttpRequest'
            , 'Content-Type':'application/x-www-form-urlencoded'
            , 'Cookie': 'JSESSIONID=' + state.loginSessionId
        ]
        , query: [
			"devicesId" : deviceId
        ]
    ]

    httpPost(params) { resp ->
        log.debug "send request Action : ${action}"
        if(resp.getStatus() == 200) {
            String actionResult = resp.data?.getText()

            if("execute success" == actionResult) {
                mainSwitch."${action}"()
            }
        }
        else {
            throw new RuntimeException()
        }

    }

}
