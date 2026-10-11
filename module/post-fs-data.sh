#!/system/bin/sh
# Present the tablet as a car head unit before Bluetooth starts.
resetprop bluetooth.profile.hfp.hf.enabled true
resetprop bluetooth.profile.map.client.enabled true
resetprop bluetooth.profile.pbap.client.enabled true
resetprop bluetooth.profile.a2dp.sink.enabled true
resetprop bluetooth.profile.avrcp.controller.enabled true
resetprop bluetooth.device.class_of_device "38,4,8"
