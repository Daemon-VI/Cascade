package com.rishi.cascade.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/** Action and trigger definitions name their icon as a string; this maps them to vectors.
 *  Unknown names fall back to a bolt rather than crashing. */
fun iconFor(name: String): ImageVector = when (name) {
    // control flow
    "CallSplit" -> Icons.Filled.CallSplit
    "AltRoute" -> Icons.Filled.AltRoute
    "SubdirectoryArrowLeft" -> Icons.Filled.SubdirectoryArrowLeft
    "Repeat" -> Icons.Filled.Repeat
    "FormatListNumbered" -> Icons.Filled.FormatListNumbered
    "Loop" -> Icons.Filled.Loop
    "Shield" -> Icons.Filled.Shield
    "ErrorOutline" -> Icons.Filled.ErrorOutline
    "Stop" -> Icons.Filled.Stop
    "FastForward" -> Icons.Filled.FastForward
    "HourglassBottom" -> Icons.Filled.HourglassBottom
    "DoNotDisturbOn" -> Icons.Filled.DoNotDisturbOn
    "ExitToApp" -> Icons.Filled.ExitToApp
    "Notes" -> Icons.Filled.Notes
    "DataObject" -> Icons.Filled.DataObject
    "PlayCircle" -> Icons.Filled.PlayCircle

    // interaction
    "QuestionAnswer" -> Icons.Filled.QuestionAnswer
    "Menu" -> Icons.Filled.Menu
    "Announcement" -> Icons.Filled.Announcement
    "Visibility" -> Icons.Filled.Visibility
    "Chat" -> Icons.Filled.Chat
    "NotificationsActive" -> Icons.Filled.NotificationsActive
    "NotificationsOff" -> Icons.Filled.NotificationsOff
    "Article" -> Icons.Filled.Article

    // text
    "TextFields" -> Icons.Filled.TextFields
    "Merge" -> Icons.Filled.Merge
    "ContentCut" -> Icons.Filled.ContentCut
    "FindReplace" -> Icons.Filled.FindReplace
    "Rule" -> Icons.Filled.Rule
    "TextFormat" -> Icons.Filled.TextFormat
    "ShortText" -> Icons.Filled.ShortText
    "Numbers" -> Icons.Filled.Numbers
    "SpaceBar" -> Icons.Filled.SpaceBar
    "ViewHeadline" -> Icons.Filled.ViewHeadline
    "SwapHoriz" -> Icons.Filled.SwapHoriz

    // maths and dates
    "Calculate" -> Icons.Filled.Calculate
    "Adjust" -> Icons.Filled.Adjust
    "Casino" -> Icons.Filled.Casino
    "QueryStats" -> Icons.Filled.QueryStats
    "Pin" -> Icons.Filled.Pin
    "SwapHorizontalCircle" -> Icons.Filled.SwapHorizontalCircle
    "Today" -> Icons.Filled.Today
    "EditCalendar" -> Icons.Filled.EditCalendar
    "MoreTime" -> Icons.Filled.MoreTime
    "Timelapse" -> Icons.Filled.Timelapse
    "CalendarViewMonth" -> Icons.Filled.CalendarViewMonth

    // data
    "ManageSearch" -> Icons.Filled.ManageSearch
    "Inventory" -> Icons.Filled.Inventory
    "Code" -> Icons.Filled.Code
    "PlaylistAdd" -> Icons.Filled.PlaylistAdd
    "FilterCenterFocus" -> Icons.Filled.FilterCenterFocus
    "EditNote" -> Icons.Filled.EditNote
    "FilterAlt" -> Icons.Filled.FilterAlt
    "Lock" -> Icons.Filled.Lock
    "Fingerprint" -> Icons.Filled.Fingerprint
    "Link" -> Icons.Filled.Link
    "Tag" -> Icons.Filled.Tag
    "Category" -> Icons.Filled.Category
    "VpnKey" -> Icons.Filled.VpnKey

    // device
    "VolumeUp" -> Icons.Filled.VolumeUp
    "VolumeDown" -> Icons.Filled.VolumeDown
    "NotificationsPaused" -> Icons.Filled.NotificationsPaused
    "BrightnessHigh" -> Icons.Filled.BrightnessHigh
    "ScreenRotation" -> Icons.Filled.ScreenRotation
    "Timer" -> Icons.Filled.Timer
    "FlashlightOn" -> Icons.Filled.FlashlightOn
    "Vibration" -> Icons.Filled.Vibration
    "ContentPaste" -> Icons.Filled.ContentPaste
    "ContentCopy" -> Icons.Filled.ContentCopy
    "BatteryFull" -> Icons.Filled.BatteryFull
    "PhoneAndroid" -> Icons.Filled.PhoneAndroid
    "Wifi" -> Icons.Filled.Wifi
    "Settings" -> Icons.Filled.Settings
    "WifiTethering" -> Icons.Filled.WifiTethering

    // apps and network
    "Apps" -> Icons.Filled.Apps
    "OpenInBrowser" -> Icons.Filled.OpenInBrowser
    "Share" -> Icons.Filled.Share
    "GridView" -> Icons.Filled.GridView
    "Info" -> Icons.Filled.Info
    "CenterFocusStrong" -> Icons.Filled.CenterFocusStrong
    "Tune" -> Icons.Filled.Tune
    "Delete" -> Icons.Filled.Delete
    "Http" -> Icons.Filled.Http
    "Download" -> Icons.Filled.Download
    "NetworkPing" -> Icons.Filled.NetworkPing
    "Public" -> Icons.Filled.Public

    // files and media
    "Description" -> Icons.Filled.Description
    "Save" -> Icons.Filled.Save
    "FolderOpen" -> Icons.Filled.FolderOpen
    "DriveFileMove" -> Icons.Filled.DriveFileMove
    "PostAdd" -> Icons.Filled.PostAdd
    "RecordVoiceOver" -> Icons.Filled.RecordVoiceOver
    "PlayArrow" -> Icons.Filled.PlayArrow
    "MusicNote" -> Icons.Filled.MusicNote
    "Mic" -> Icons.Filled.Mic
    "Wallpaper" -> Icons.Filled.Wallpaper
    "Launch" -> Icons.Filled.Launch

    // location, comms, scripting
    "MyLocation" -> Icons.Filled.MyLocation
    "Search" -> Icons.Filled.Search
    "Straighten" -> Icons.Filled.Straighten
    "Sms" -> Icons.Filled.Sms
    "Call" -> Icons.Filled.Call
    "Email" -> Icons.Filled.Email
    "Contacts" -> Icons.Filled.Contacts
    "Javascript" -> Icons.Filled.Javascript
    "Terminal" -> Icons.Filled.Terminal

    // triggers
    "Dashboard" -> Icons.Filled.Dashboard
    "AddToHomeScreen" -> Icons.Filled.AddToHomeScreen
    "Schedule" -> Icons.Filled.Schedule
    "Update" -> Icons.Filled.Update
    "RestartAlt" -> Icons.Filled.RestartAlt
    "BatteryChargingFull" -> Icons.Filled.BatteryChargingFull
    "PowerOff" -> Icons.Filled.PowerOff
    "Battery2Bar" -> Icons.Filled.Battery2Bar
    "Headphones" -> Icons.Filled.Headphones
    "HeadsetOff" -> Icons.Filled.HeadsetOff
    "LightMode" -> Icons.Filled.LightMode
    "DarkMode" -> Icons.Filled.DarkMode
    "LockOpen" -> Icons.Filled.LockOpen
    "WifiOff" -> Icons.Filled.WifiOff
    "Notifications" -> Icons.Filled.Notifications
    "AirplanemodeActive" -> Icons.Filled.AirplanemodeActive

    // flow icons
    "Home" -> Icons.Filled.Home
    "Work" -> Icons.Filled.Work
    "DirectionsCar" -> Icons.Filled.DirectionsCar
    "Bedtime" -> Icons.Filled.Bedtime
    "WbSunny" -> Icons.Filled.WbSunny
    "FitnessCenter" -> Icons.Filled.FitnessCenter
    "School" -> Icons.Filled.School
    "ShoppingCart" -> Icons.Filled.ShoppingCart
    "Coffee" -> Icons.Filled.Coffee
    "Alarm" -> Icons.Filled.Alarm
    "PhotoCamera" -> Icons.Filled.PhotoCamera
    "Map" -> Icons.Filled.Map
    "Favorite" -> Icons.Filled.Favorite
    "Flight" -> Icons.Filled.Flight
    "Restaurant" -> Icons.Filled.Restaurant
    "SportsEsports" -> Icons.Filled.SportsEsports

    else -> Icons.Filled.Bolt
}

/** Icons offered when picking a flow's badge. */
val FlowIcons = listOf(
    "Bolt", "Home", "Work", "Wifi", "MusicNote", "DirectionsCar", "Bedtime", "WbSunny",
    "FitnessCenter", "School", "ShoppingCart", "Coffee", "Alarm", "PhotoCamera", "Map",
    "Favorite", "Flight", "Restaurant", "SportsEsports", "Timer", "Settings", "Chat"
)
