package com.softppj.hometv

import android.app.*
import android.os.*
import android.content.*
import android.view.*
import android.widget.*
import android.webkit.*
import android.net.Uri
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media as VlcMedia
import org.videolan.libvlc.MediaPlayer as VlcPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

data class Channel(val name:String, val url:String, val group:String, val logo:String="", val source:String="", val headers:Map<String,String> = emptyMap(), val epgId:String="", val webChannel:String="", val mediaType:String="live", val year:String="", val genre:String="", val artist:String="", val album:String="", val season:String="", val episode:String="", val description:String="")
data class Playlist(val name:String, val url:String, val enabled:Boolean=true)

class MainActivity: Activity() {
    private val prefs by lazy { getSharedPreferences("hometv", MODE_PRIVATE) }
    private var channels = mutableListOf<Channel>()
    // Licensing is verified online before any cached or remote playlist is opened.
    private val licenseApi="https://hometv-license.prasanthpjoy.workers.dev"
    private val licensedPlaylist="hometv://licensed-playlist"
    private var licenseReady=false
    private var licenseChecking=false
    private var licenseOperationGeneration=0
    private fun saveLicenseExpiry(response:JSONObject){
        response.optString("expiry","").takeIf{it.isNotBlank()}?.let{prefs.edit().putString("license_expiry",it).apply()}
    }
    private var surfingDirection=1
    private fun installationId():String {
        val old=prefs.getString("license_device",null)
        if(old!=null)return old
        val id=java.util.UUID.randomUUID().toString()
        prefs.edit().putString("license_device",id).commit()
        return id
    }
    private fun licenseRequest(endpoint:String,payload:JSONObject):JSONObject {
        val conn=(URL(licenseApi+endpoint).openConnection() as java.net.HttpURLConnection)
        try {
            conn.requestMethod="POST";conn.doOutput=true;conn.connectTimeout=12000;conn.readTimeout=15000
            conn.setRequestProperty("Content-Type","application/json")
            conn.outputStream.use{it.write(payload.toString().toByteArray(Charsets.UTF_8))}
            val status=conn.responseCode
            val body=(if(status in 200..299)conn.inputStream else conn.errorStream)?.bufferedReader()?.use{it.readText()} ?: ""
            val result=try{JSONObject(body)}catch(_:Exception){JSONObject()}
            if(status !in 200..299)throw IllegalStateException(result.optString("error","License server error: "+status))
            // Never treat a 2xx HTTP response with an explicit rejection
            // or a malformed body as a valid license.
            if(body.isBlank() || result.length()==0)
                throw IllegalStateException("Invalid license server response")
            if(result.has("valid") && !result.optBoolean("valid",false))
                throw IllegalStateException(result.optString("error","License rejected"))
            if(result.has("success") && !result.optBoolean("success",false))
                throw IllegalStateException(result.optString("error","License request rejected"))
            return result
        }finally{conn.disconnect()}
    }
    private fun licensePayload():JSONObject=JSONObject().put("device",installationId()).put("token",prefs.getString("license_token",""))
    private fun licensedM3u():String {
        val conn=(URL(licenseApi+"/playlist").openConnection() as java.net.HttpURLConnection)
        try {
            conn.requestMethod="POST";conn.doOutput=true;conn.connectTimeout=12000;conn.readTimeout=20000
            conn.setRequestProperty("Content-Type","application/json")
            conn.outputStream.use{it.write(licensePayload().toString().toByteArray(Charsets.UTF_8))}
            val status=conn.responseCode
            val data=(if(status in 200..299)conn.inputStream else conn.errorStream)?.bufferedReader()?.use{it.readText()} ?: ""
            if(status !in 200..299)throw IllegalStateException(try{JSONObject(data).optString("error","License validation failed")}catch(_:Exception){"License validation failed"})
            if(!data.trimStart().startsWith("#EXTM3U"))throw IllegalStateException("Invalid licensed playlist")
            return data
        }finally{conn.disconnect()}
    }
    private fun showLicenseCheckingScreen(){
        // License checks must not leave a previous video or its audio running.
        stopPlayback()
        val screen=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER
            setPadding(32,32,32,32)
            setBackgroundColor(0xFF101E31.toInt())
        }
        screen.addView(ImageView(this).apply{
            setImageResource(R.drawable.hometv_icon)
            scaleType=ImageView.ScaleType.FIT_CENTER
        },LinearLayout.LayoutParams((160*resources.displayMetrics.density).toInt(),(160*resources.displayMetrics.density).toInt()))
        screen.addView(TextView(this).apply{
            text="HomeTV"
            textSize=32f
            gravity=Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setPadding(0,20,0,12)
        })
        screen.addView(ProgressBar(this))
        screen.addView(TextView(this).apply{
            text="Checking license…"
            textSize=17f
            gravity=Gravity.CENTER
            setTextColor(0xFFB6C9DD.toInt())
            setPadding(0,20,0,0)
        })
        setContentView(screen)
    }
    private fun beginLicensedSession(){
        if(licenseChecking)return
        val token=prefs.getString("license_token",null)
        if(token.isNullOrBlank()){
            licenseReady=false
            showLicenseScreen()
            return
        }
        // Fail closed: a previously validated token is not authorization to
        // play cached streams before this session's server validation succeeds.
        licenseReady=false
        licenseChecking=true
        val operation=++licenseOperationGeneration
        showLicenseCheckingScreen()
        thread{
            val result=try{
                val response=licenseRequest("/validate",licensePayload())
                saveLicenseExpiry(response)
                null
            }catch(e:Exception){e.message?:"License validation failed"}
            runOnUiThread{
                if(operation!=licenseOperationGeneration || isFinishing || isDestroyed)return@runOnUiThread
                licenseChecking=false
                if(result==null){
                    prefs.edit().putBoolean("license_last_verified",true)
                        .putBoolean("license_validation_blocked",false).apply()
                    licenseReady=true
                    startLicensedApp()
                }else{
                    prefs.edit().putBoolean("license_validation_blocked",true)
                        .putBoolean("license_last_verified",false).commit()
                    licenseReady=false
                    if(result.contains("expired",true)||result.contains("revoked",true)||
                        result.contains("not active",true)||result.contains("Invalid token",true))
                        prefs.edit().remove("license_token").remove("license_expiry").apply()
                    showLicenseScreen("License verification failed: "+result)
                }
            }
        }
    }
    private fun startLicensedApp(){
        if(!licenseReady)return
        // Discard cached-load results from an earlier activation/session.
        val operation=licenseOperationGeneration
        showLicenseCheckingScreen()
        thread{
            val cached=try{cachedChannels()}catch(e:Exception){
                android.util.Log.w("HomeTV","Cached playlist parse failed",e)
                mutableListOf<Channel>()
            }
            runOnUiThread{
                if(operation!=licenseOperationGeneration || !licenseReady || isFinishing || isDestroyed)return@runOnUiThread
                channels=cached
                checkEpgTune()
                refresh({
                    if(!licenseReady)return@refresh
                    if(channels.isNotEmpty())openStartupDestination() else showHome()
                })
            }
        }
    }
    private fun openStartupDestination(){
        when(prefs.getString("launch_screen","Last channel") ?: "Last channel"){
            "Home"->showHome()
            "Channels"->allChannels()
            else->if(prefs.getBoolean("resume",true))resumeLast() else showHome()
        }
    }
    private fun showLicenseScreen(error:String?=null){
        licenseReady=false
        licenseOperationGeneration++
        // Invalidate a pending playlist refresh so it cannot publish old
        // results after authorization has been revoked or surrendered.
        refreshGeneration++
        stopPlayback()
        reset("HomeTV • License activation")
        root.addView(label("Enter your HomeTV License Key"))
        if(!error.isNullOrBlank())root.addView(label(error))
        val key=EditText(this).apply{
            hint="HTV-XXXXX-XXXXX-XXXXX-XXXXX"
            isSingleLine=true
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            setTextColor(android.graphics.Color.WHITE);setHintTextColor(0xFFB0BEC5.toInt())
            setPadding(16,20,16,20)
        }
        root.addView(key)
        if(!prefs.getString("license_token",null).isNullOrBlank()){
            root.addView(button("Retry Existing License"){
                beginLicensedSession()
            })
        }
        val activate=button("Activate HomeTV"){
            val value=key.text.toString().trim().uppercase(java.util.Locale.ROOT)
            if(value.isBlank()){key.error="Enter a license key";return@button}
            key.isEnabled=false
            val operation=++licenseOperationGeneration
            reset("HomeTV • Activating license")
            thread{
                var token:String?=null
                var activationResult:JSONObject?=null
                val error=try{
                    val result=licenseRequest("/activate",JSONObject().put("device",installationId()).put("password",value))
                    activationResult=result
                    token=result.getString("token").takeIf{it.isNotBlank()}
                    if(token==null)throw IllegalStateException("Activation returned an empty token")
                    null
                }catch(e:Exception){e.message?:"Activation failed"}
                runOnUiThread{
                    if(operation!=licenseOperationGeneration || isFinishing || isDestroyed)return@runOnUiThread
                    if(token!=null){
                        prefs.edit().putString("license_token",token).putBoolean("license_last_verified",true).putBoolean("license_validation_blocked",false).apply()
                        activationResult?.let{saveLicenseExpiry(it)}
                        licenseReady=true;startLicensedApp()
                    }else showLicenseScreen(error)
                }
            }
        }
        root.addView(activate)
        root.addView(label("An internet connection is required for activation and license checks."))
    }
    private fun surrenderLicense(){
        AlertDialog.Builder(this).setTitle("Surrender License")
            .setMessage("Release this device from its license key? You will need a key to activate again.")
            .setPositiveButton("Surrender"){_,_->
                val operation=++licenseOperationGeneration
                licenseReady=false
                stopPlayback()
                reset("HomeTV • Releasing license")
                thread{
                    val error=try{licenseRequest("/surrender",licensePayload());null}catch(e:Exception){e.message?:"Unable to release license"}
                    runOnUiThread{
                        if(operation!=licenseOperationGeneration || isFinishing || isDestroyed)return@runOnUiThread
                        if(error==null){
                            prefs.edit().remove("license_token").remove("license_expiry").putBoolean("license_last_verified",false).putBoolean("license_validation_blocked",true).apply()
                            licenseReady=false;channels.clear();showLicenseScreen("Device released successfully.")
                        }else{
                            // Server did not confirm surrender: retain the token,
                            // but require fresh validation before playback resumes.
                            showLicenseScreen("Could not surrender: "+error)
                        }
                    }
                }
            }.setNegativeButton("Cancel",null).show()
    }

    private var player:ExoPlayer?=null
    private var ijkPlayer:IjkMediaPlayer?=null
    private var ijkSurface:SurfaceView?=null
    private var ijkVideoWidth=0
    private var ijkVideoHeight=0
    private var vlcCore:LibVLC?=null
    private var vlcPlayer:VlcPlayer?=null
    private var vlcAttempted=false
    private val engineAttempts=mutableMapOf<String,MutableSet<String>>()
    private val recoveryAttempts=mutableMapOf<String,Int>()
    private val unavailableChannels=mutableSetOf<String>()
    private val playedThisSession=mutableSetOf<String>()
    private val failedUrlsThisSession=mutableSetOf<String>()
    private var fallbackEngineOverride:String?=null
    private var engineChannelUrl:String?=null
    private var web:WebView?=null
    private var active=-1
    private var previousChannelUrl:String?=null
    private var previousChannelIdentity:String?=null
    private var alternativeAttempted=mutableSetOf<String>()
    private var alternativeSessionKey:String?=null
    private var playbackGeneration=0
    private var startupWatchdog:Runnable?=null
    private var channelIndexOwner:MutableList<Channel>?=null
    private var channelIndexSize=-1
    private var channelRepresentatives=emptyList<Int>()
    private var liveChannelRepresentatives=emptyList<Int>()
    private var channelByKey=emptyMap<String,List<Int>>()
    private var channelPosition=emptyMap<String,Int>()
    private fun channelKey(c:Channel)=c.mediaType+"|"+c.source.trim().lowercase(java.util.Locale.ROOT)+"|"+
        c.name.trim().lowercase(java.util.Locale.ROOT)+"|"+c.group.trim().lowercase(java.util.Locale.ROOT)
    private fun ensureChannelIndex(){
        if(channelIndexOwner===channels && channelIndexSize==channels.size)return
        val groups=linkedMapOf<String,MutableList<Int>>()
        channels.forEachIndexed{i,c->groups.getOrPut(channelKey(c)){mutableListOf()}.add(i)}
        channelByKey=groups
        channelRepresentatives=groups.values.map{it.first()}
        liveChannelRepresentatives=channelRepresentatives.filter{channels[it].mediaType=="live"}
        // Channel numbers refer to live logical channels only, never VOD
        // entries or additional URLs for the same channel.
        channelPosition=liveChannelRepresentatives.withIndex().associate{(position,index)->
            channelKey(channels[index]) to position+1
        }
        channelIndexOwner=channels
        channelIndexSize=channels.size
    }
    // Parallel, bounded URL reachability checks; no extra video decoders or player switches.
    private fun checkChannelAlternativesInParallel(index:Int){
        val candidates=alternateIndices(index).asSequence().distinctBy{channels[it].url}.filter{candidate->
            val url=channels[candidate].url.lowercase(java.util.Locale.ROOT)
            (url.startsWith("http://") || url.startsWith("https://")) &&
                !url.contains("youtube.com") && !url.contains("youtu.be") &&
                !prefs.getBoolean("libvlc_only_"+hash(channels[candidate].url),false)
        }.take(12).toList()
        if(candidates.size<2)return
        val generation=playbackGeneration
        // Bound both candidate discovery and network checks on huge playlists.
        val queue=java.util.concurrent.ConcurrentLinkedQueue(candidates)
        repeat(minOf(3,queue.size)){
            thread(name="HomeTV-channel-url-check"){
                while(true){
                    val candidate=queue.poll() ?:break
                    if(generation!=playbackGeneration)break
                    val channel=channels.getOrNull(candidate) ?:continue
                    val reachable=try{
                        val connection=URL(channel.url).openConnection() as java.net.HttpURLConnection
                        try{
                            connection.connectTimeout=2500
                            connection.readTimeout=2500
                            connection.instanceFollowRedirects=true
                            connection.setRequestProperty("Range","bytes=0-1023")
                            connection.setRequestProperty("User-Agent",channel.headers["User-Agent"] ?: "HomeTV/1.0 AndroidTV")
                            channel.headers.forEach{(key,value)->
                                if(!key.equals("User-Agent",true))connection.setRequestProperty(key,value)
                            }
                            connection.responseCode in 200..299 &&
                                !(connection.contentType ?: "").contains("text/html",true)
                        }finally{connection.disconnect()}
                    }catch(_:Exception){false}
                    if(generation==playbackGeneration)probeReachable[channel.url]=reachable
                }
            }
        }
    }
    private fun preferredChannel(index:Int):Int{
        val c=channels.getOrNull(index) ?:return index
        ensureChannelIndex()
        val group=channelByKey[channelKey(c)] ?:return index
        val saved=prefs.getString("working_url_"+channelKey(c),null)
        val manual=prefs.getString("manual_url_"+channelKey(c),null)
        // One scan rather than repeatedly filtering large alternative-source groups.
        var firstUsable:Int?=null
        var savedMatch:Int?=null
        var reachableMatch:Int?=null
        var vlcMatch:Int?=null
        for(candidate in group){
            val url=channels[candidate].url
            if(url in failedUrlsThisSession)continue
            if(firstUsable==null)firstUsable=candidate
            if(manual!=null && url==manual)return candidate
            if(savedMatch==null && saved!=null && url==saved)savedMatch=candidate
            if(reachableMatch==null && probeReachable[url]==true)reachableMatch=candidate
            if(vlcMatch==null && vlcProbeReachable[url]==true)vlcMatch=candidate
        }
        return savedMatch ?:reachableMatch ?:vlcMatch ?:firstUsable ?:group.first()
    }
    private fun rememberWorking(c:Channel){
        playedThisSession.add(channelKey(c))
        recoveryPrompted.remove(channelKey(c))
        failedUrlsThisSession.remove(c.url)
        unavailableChannels.remove(channelKey(c))
        // Record the player that actually reached playback, not a pending fallback hint.
        val engine=when{ijkPlayer!=null->"IJKPlayer";vlcPlayer!=null->"LibVLC";else->"Media3"}
        // Keep the confirmed engine paired with the URL that actually played.
        // A manually pinned URL must not be silently replaced by a tested alternative.
        val confirmedKey=channelKey(c)
        val pinnedUrl=prefs.getString("manual_url_"+confirmedKey,null)
        if(testingAlternativeKey!=confirmedKey && (pinnedUrl==null || pinnedUrl==c.url)){
            prefs.edit()
                .putString("confirmed_engine_"+confirmedKey,engine)
                .putString("working_url_"+confirmedKey,c.url)
                .apply()
        }
        initialTried.remove(channelKey(c))
        if(engine=="Media3"){
            probeReachable[c.url]=true
            prefs.edit().putBoolean("media3_verified_"+hash(c.url),true)
                .remove("libvlc_only_"+hash(c.url)).apply()
        }else if(engine=="LibVLC"){
            // Keep the proven working engine for this URL across channel changes/restarts.
            prefs.edit().putBoolean("libvlc_only_"+hash(c.url),true).apply()
        }
        engineAttempts.remove(c.url)
        // Preserve a user's explicit stream choice until they change it.
        // Automatically confirmed alternatives remain usable as fallbacks,
        // but must not silently replace the manual preference.
        if(testingAlternativeKey==channelKey(c))testingAlternativeKey=null
    }
    private var youtubeUnmuteTask:Runnable?=null
    private lateinit var root:LinearLayout
    private val lockIds get() = prefs.getStringSet("locked_channels", emptySet()) ?: emptySet()
    private val lockGroups get() = prefs.getStringSet("locked_groups", emptySet()) ?: emptySet()
    private var unlockedChannel:String?=null
    private var videoScreen:FrameLayout?=null
    private var currentPlayerView:PlayerView?=null
    private val handler=Handler(Looper.getMainLooper())
    private var banner:View?=null
    private var bannerTask:Runnable?=null
    private var clockTask:Runnable?=null
    private var fallbackAttempted=mutableSetOf<String>()
    // Viewer-specific preferences; license, playlists and parental restrictions remain shared.
    private fun viewerId():String=prefs.getString("active_viewer","default") ?: "default"
    private fun viewerKey(key:String):String=if(viewerId()=="default")key else "viewer_"+viewerId()+"_"+key
    private val favoriteIds get()=prefs.getStringSet(viewerKey("favorites"),emptySet()) ?: emptySet()
    private fun manageViewerProfiles(){
        val names=org.json.JSONArray(prefs.getString("viewer_profiles","[]"))
        val entries=mutableListOf("Default")
        for(i in 0 until names.length())entries.add(names.optString(i))
        val options=entries+listOf("＋ Add viewer","− Remove current viewer")
        val selected=if(viewerId()=="default")0 else entries.indexOfFirst{it==viewerId()}.coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("Viewer profiles")
            .setSingleChoiceItems(options.toTypedArray(),selected){dialog,which->
                dialog.dismiss()
                when {
                    which<entries.size->{
                        prefs.edit().putString("active_viewer",if(which==0)"default" else entries[which]).apply()
                        settings()
                    }
                    which==entries.size->{
                        val field=EditText(this).apply{hint="Viewer name (letters and numbers)"}
                        AlertDialog.Builder(this).setTitle("Add viewer").setView(field)
                            .setPositiveButton("Add"){_,_->
                                val name=field.text.toString().trim()
                                if(name.matches(Regex("[A-Za-z0-9 _-]{1,24}")) && name!="Default" && !entries.contains(name)){
                                    names.put(name)
                                    prefs.edit().putString("viewer_profiles",names.toString()).putString("active_viewer",name).apply()
                                    settings()
                                }else Toast.makeText(this,"Enter a unique name (1–24 letters/numbers)",Toast.LENGTH_LONG).show()
                            }.setNegativeButton("Cancel",null).show()
                    }
                    viewerId()!="default"->{
                        val old=viewerId()
                        AlertDialog.Builder(this).setTitle("Remove "+old+"?")
                            .setMessage("This removes the viewer's Favorites and history, not shared playlists.")
                            .setPositiveButton("Remove"){_,_->
                                val remaining=JSONArray()
                                for(i in 0 until names.length())if(names.optString(i)!=old)remaining.put(names.optString(i))
                                prefs.edit().putString("viewer_profiles",remaining.toString())
                                    .putString("active_viewer","default")
                                    .remove("viewer_"+old+"_favorites").remove("viewer_"+old+"_recent_channels").apply()
                                settings()
                            }.setNegativeButton("Cancel",null).show()
                    }
                }
            }.setNegativeButton("Close",null).show()
    }
    private var retryCount=0
    private var retryUrl:String?=null
    private var retryTask:Runnable?=null
    private fun favorite(c:Channel){
        val set=favoriteIds.toMutableSet()
        if(!set.add(id(c)))set.remove(id(c))
        prefs.edit().putStringSet(viewerKey("favorites"),set).apply()
        Toast.makeText(this,if(set.contains(id(c)))"Added to Favorites" else "Removed from Favorites",Toast.LENGTH_SHORT).show()
    }
    private val skippedIds get()=prefs.getStringSet("skip_channel_surfing",emptySet()) ?: emptySet()
    private fun isSkipped(c:Channel):Boolean{
        ensureChannelIndex()
        return (channelByKey[channelKey(c)] ?:emptyList()).any{skippedIds.contains(id(channels[it]))}
    }
    private fun selectedPlaylistSource():String?{
        val selected=prefs.getString("selected_playlist_source",null) ?:return null
        return selected.takeIf{source->playlists().any{it.enabled && it.name==source} &&
            channels.any{it.source==source}}
    }
    private fun playlistIndices():List<Int>{
        val selected=selectedPlaylistSource()
        return visibleIndices().filter{selected==null || channels[it].source==selected}
    }
    private fun surfingIndices():List<Int>{
        val base=playlistIndices().filter{
            !isSkipped(channels[it]) &&
            !isLocked(channels[it]) &&
            !isManuallyHidden(channels[it]) &&
            !isGroupHidden(channels[it].group) &&
            !temporaryFailureHidden.contains(channelKey(channels[it])) &&
            !unavailableChannels.contains(channelKey(channels[it]))
        }
        val favoritesOnly=prefs.getBoolean("favorites_surfing",false)
        val filtered=if(favoritesOnly)base.filter{favoriteIds.contains(id(channels[it]))} else base
        return orderedChannelIndices(if(favoritesOnly)"Favorites" else "All channels",filtered)
    }
    private fun toggleChannelSkip(c:Channel){
        val values=skippedIds.toMutableSet()
        val skipped=!isSkipped(c)
        alternateIndices(channels.indexOf(c)).forEach{val entry=channels[it];if(skipped)values.add(id(entry)) else values.remove(id(entry))}
        prefs.edit().putStringSet("skip_channel_surfing",values).apply()
        Toast.makeText(this,if(skipped)"Skipped during channel surfing: "+c.name else "Included in channel surfing: "+c.name,Toast.LENGTH_SHORT).show()
    }
    private fun manageSkippedChannels(){
        val skipped=visibleIndices().filter{isSkipped(channels[it])}
        if(skipped.isEmpty()){
            Toast.makeText(this,"No channels are skipped",Toast.LENGTH_SHORT).show()
            return
        }
        val rows=skipped.toMutableList()
        val labels=rows.map{(it+1).toString()+". "+channels[it].name}.toMutableList()
        val adapter=android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels)
        val dialog=AlertDialog.Builder(this).setTitle("Restore skipped channels")
            .setAdapter(adapter){_,_->}
            .setNegativeButton("Close",null).create()
        dialog.setOnShowListener{
            dialog.listView?.setOnItemClickListener{_,_,position,_->
                val selected=rows.getOrNull(position) ?:return@setOnItemClickListener
                toggleChannelSkip(channels[selected])
                rows.removeAt(position)
                labels.removeAt(position)
                adapter.notifyDataSetChanged()
                dialog.setTitle("Restore skipped channels ("+rows.size+" remaining)")
            }
        }
        showSideDialog(dialog)
    }
    private fun sameChannel(a:Channel,b:Channel)=channelKey(a)==channelKey(b)
    private fun alternateIndices(index:Int):List<Int>{
        val channel=channels.getOrNull(index) ?:return emptyList()
        ensureChannelIndex()
        return (channelByKey[channelKey(channel)] ?:emptyList()).distinctBy{channels[it].url}
    }
    private fun switchAlternative(){
        if(active !in channels.indices)return
        val options=alternateIndices(active)
        if(options.size<=1){showBanner("No alternative stream");return}
        val current=options.indexOf(active)
        alternativeAttempted.clear()
        alternativeSessionKey=null
        val selected=channels[options[(current+1)%options.size]]
        val selectedKey=channelKey(selected)
        val editor=prefs.edit().putString("manual_url_"+selectedKey,selected.url)
        if(prefs.getString("working_url_"+selectedKey,null)!=selected.url)
            editor.remove("working_url_"+selectedKey).remove("confirmed_engine_"+selectedKey)
        editor.apply()
        playIndex(options[(current+1)%options.size])
        showBanner("Source "+((current+1)%options.size+1)+" of "+options.size)
    }
    private fun selectAlternative(index:Int){
        val options=alternateIndices(index)
        if(options.size<=1){
            Toast.makeText(this,"No alternative stream for this channel",Toast.LENGTH_SHORT).show()
            return
        }
        val selectionGeneration=playbackGeneration
        val selectionScreen=videoScreen
        val dialog=AlertDialog.Builder(this).setTitle(channels[index].name+" — Alternative streams")
            .setItems(options.mapIndexed{n,i->
                "Stream option "+(n+1)+(if(i==active)"  •  Playing" else "")
            }.toTypedArray()){_,choice->
                if(active!=index || videoScreen!==selectionScreen || playbackGeneration!=selectionGeneration)return@setItems
                val target=options[choice]
                alternativeAttempted.clear()
                alternativeSessionKey=null
                val selected=channels[target]
                val selectedKey=channelKey(selected)
                val editor=prefs.edit().putString("manual_url_"+selectedKey,selected.url)
                if(prefs.getString("working_url_"+selectedKey,null)!=selected.url)
                    editor.remove("working_url_"+selectedKey).remove("confirmed_engine_"+selectedKey)
                editor.apply()
                playIndex(target)
            }.setNegativeButton("Cancel",null).create()
        showSideDialog(dialog)
    }
    private fun tryNextAlternative(index:Int):Boolean{
        if(index !in channels.indices)return false
        val options=alternateIndices(index)
        alternativeAttempted.add(channels[index].url)
        val next=options.firstOrNull{!alternativeAttempted.contains(channels[it].url)}
            ?:return false
        showBanner("Trying alternative source for "+channels[index].name)
        val sourceScreen=videoScreen
        val sourceGeneration=playbackGeneration
        handler.post{
            if(active==index && videoScreen===sourceScreen && playbackGeneration==sourceGeneration)
                playIndex(next)
        }
        return true
    }
    private fun hasNetworkConnection():Boolean{
        val manager=getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        return try{manager?.activeNetworkInfo?.isConnected==true}catch(_:Exception){true}
    }
    private var networkRecoveryTask:Runnable?=null
    private fun awaitNetworkRecovery(index:Int,screen:FrameLayout){
        networkRecoveryTask?.let{handler.removeCallbacks(it)}
        if(index !in channels.indices || active!=index || videoScreen!==screen)return
        showBanner("Internet disconnected • Waiting to reconnect",true)
        val recoveryGeneration=playbackGeneration
        val task=object:Runnable{
            override fun run(){
                if(active!=index || videoScreen!==screen || playbackGeneration!=recoveryGeneration || networkRecoveryTask!==this)return
                if(hasNetworkConnection()){
                    networkRecoveryTask=null
                    playIndex(index)
                }else handler.postDelayed(this,3000L)
            }
        }
        networkRecoveryTask=task
        handler.postDelayed(task,3000L)
    }
    private val temporaryFailureHidden=mutableSetOf<String>()
    private val initialTried=mutableMapOf<String,MutableSet<String>>()
    private val recoveryPrompted=mutableSetOf<String>()
    private var failurePromptKey:String?=null
    private val failureSearches=mutableSetOf<String>()
    private fun moveAfterFailedChannelHidden(index:Int,screen:FrameLayout){
        if(active!=index || videoScreen!==screen)return
        val available=surfingIndices().filter{candidate->
            candidate in channels.indices && channelKey(channels[candidate])!=channelKey(channels[index])
        }
        val next=nextSurfingIndex(index,surfingDirection,available)
        if(next>=0)playIndex(preferredChannel(next))
        else showHome() // No other surfable channel remains.
    }
    private fun promptFailedChannel(index:Int,screen:FrameLayout){
        if(index !in channels.indices || active!=index || videoScreen!==screen)return
        val c=channels[index]
        val key=channelKey(c)
        if(prefs.contains("confirmed_engine_"+key) &&
            !prefs.getString("working_url_"+key,null).isNullOrBlank())return
        if(failurePromptKey==key || prefs.getBoolean("first_failure_prompted_"+key,false))return
        failurePromptKey=key
        // Prompt only once per channel identity, even after an app restart.
        prefs.edit().putBoolean("first_failure_prompted_"+key,true).apply()
        val dialog=AlertDialog.Builder(this)
            .setTitle(c.name+" — no playable source found")
            .setMessage("All URL and player combinations failed on this first attempt. What would you like to do?")
            .setPositiveButton("Retry all"){_,_->
                failurePromptKey=null
                recoveryPrompted.remove(key)
                initialTried.remove(key)
                recoveryAttempts.remove(c.url)
                fallbackEngineOverride=null
                if(active==index && videoScreen===screen)playIndex(preferredChannel(index))
            }
            .setNeutralButton("Hide until restart"){_,_->
                failurePromptKey=null
                temporaryFailureHidden.add(key)
                unavailableChannels.add(key)
                moveAfterFailedChannelHidden(index,screen)
            }
            .setNegativeButton("Hide permanently"){_,_->
                failurePromptKey=null
                if(!isManuallyHidden(c))toggleManualHide(c)
                moveAfterFailedChannelHidden(index,screen)
            }
            .setOnCancelListener{failurePromptKey=null}
            .create()
        dialog.setOnShowListener{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }
        dialog.show()
    }
    private fun checkFailedChannelAlternatives(index:Int,screen:FrameLayout){
        if(index !in channels.indices || active!=index || videoScreen!==screen)return
        val c=channels[index]
        val key=channelKey(c)
        if(!failureSearches.add(key))return
        val searchGeneration=playbackGeneration
        val candidates=alternateIndices(index).filter{it!=index}.take(12)
        if(candidates.isEmpty()){
            failureSearches.remove(key)
            return
        }
        thread(name="HomeTV-recovery-probe"){
            var found:Int?=null
            for(candidate in candidates){
                val entry=channels[candidate]
                try{
                    val connection=URL(entry.url).openConnection() as java.net.HttpURLConnection
                    try{
                        connection.connectTimeout=3500
                        connection.readTimeout=3500
                        connection.instanceFollowRedirects=true
                        entry.headers.forEach{(k,v)->connection.setRequestProperty(k,v)}
                        if(connection.responseCode in 200..299 &&
                            !(connection.contentType ?: "").contains("text/html",true)){
                            found=candidate
                            break
                        }
                    }finally{connection.disconnect()}
                }catch(_:Exception){}
            }
            handler.post{
                failureSearches.remove(key)
                if(active!=index || videoScreen!==screen || playbackGeneration!=searchGeneration)return@post
                val alternative=found
                if(alternative!=null){
                    AlertDialog.Builder(this).setTitle("Alternative source available")
                        .setMessage("A different URL for "+c.name+" responded successfully. This does not guarantee playback. Test it and save it only if it plays?")
                        .setPositiveButton("Test alternative"){_,_->
                            if(active==index && videoScreen===screen && playbackGeneration==searchGeneration){
                                // Test without overwriting the confirmed source.
                                testingAlternativeKey=key
                                fallbackEngineOverride=null
                                playIndex(alternative)
                            }
                        }
                        .setNegativeButton("Keep current",null).show()
                } // No candidate: keep retrying the saved stream silently.
            }
        }
    }
    private var testingAlternativeKey:String?=null
    private fun handleEngineFailure(index:Int,screen:FrameLayout):Boolean{
        if(!hasNetworkConnection()){
            awaitNetworkRecovery(index,screen)
            return true
        }
        if(index !in channels.indices || active!=index || videoScreen!==screen)return true
        val c=channels[index]
        val key=channelKey(c)
        val current=when{ijkPlayer!=null->"IJKPlayer";vlcPlayer!=null->"LibVLC";else->"Media3"}
        // Manual engine preference selects the first attempt, not an infinite
        // retry of the same failed engine. The other engine remains a fallback.
        // A confirmed engine is only proven for its confirmed URL, not every
        // alternative URL of the same logical channel.
        val saved=prefs.contains("confirmed_engine_"+key) &&
            prefs.getString("working_url_"+key,null)==c.url
        if(!saved && testingAlternativeKey!=key){
            // Initial discovery: try each distinct URL with Media3 and LibVLC.
            // Only a real player-ready/playing event records a successful combination.
            val tried=initialTried.getOrPut(key){mutableSetOf()}
            tried.add(c.url+"|"+current)
            // Exhaust both engines for the selected source before probing
            // unrelated URLs. Keep a manually selected source first.
            // Stream alternatives can number in the thousands. Avoid
            // materializing two Pair objects per source on every failure.
            val candidates=sequenceOf(index)+alternateIndices(index).asSequence().filter{it!=index}
            val next=candidates.flatMap{i->
                sequenceOf(Pair(i,"Media3"),Pair(i,"LibVLC"),Pair(i,"IJKPlayer"))
            }.firstOrNull{(i,engine)->
                !tried.contains(channels[i].url+"|"+engine)
            }
            if(next!=null){
                fallbackEngineOverride=next.second
                showBanner("Finding working source for "+c.name)
                val failedSessionGeneration=playbackGeneration
                val task=Runnable{if(active==index && videoScreen===screen && playbackGeneration==failedSessionGeneration)playIndex(next.first)}
                retryTask=task
                handler.postDelayed(task,900L)
                return true
            }
            // All URL/player combinations have failed. Auto-skip must be
            // controlled by its own ON/OFF setting, not by background checking.
            if(prefs.getBoolean("auto_skip_nonworking",true)){
                unavailableChannels.add(key)
                showBanner("No playable source for "+c.name+" • Skipping")
                moveAfterFailedChannelHidden(index,screen)
                return true
            }
            // With auto-skip OFF, remain on this channel unless the user
            // explicitly chooses to hide it in the one-time failure prompt.
            if(!prefs.getBoolean("first_failure_prompted_"+key,false)){
                promptFailedChannel(index,screen)
                return true
            }
        }
        // Confirmed sources never probe or switch to alternatives automatically.
        // Alternative selection is available manually from channel settings.
        if(!prefs.getBoolean("auto_reconnect",true)){
            showBanner("Stream unavailable • Auto-reconnect is OFF",true)
            return true
        }
        fallbackEngineOverride=current
        val count=(recoveryAttempts[c.url] ?: 0)+1
        recoveryAttempts[c.url]=count
        // A broken confirmed stream must not reconnect forever. Preserve its
        // saved preference and allow the viewer to select another source.
        if(count>6){
            showBanner("Stream unavailable after 6 reconnects • Select another source",true)
            return true
        }
        if(count==1 || count%5==0)showBanner("Reconnecting "+c.name+" with "+current)
        val recoveryGeneration=playbackGeneration
        val task=Runnable{
            if(active==index && videoScreen===screen && playbackGeneration==recoveryGeneration){
                fallbackEngineOverride=current
                playIndex(index)
            }
        }
        retryTask=task
        handler.postDelayed(task,5000L)
        return true
    }
    private fun isLocked(c:Channel):Boolean{
        if(lockGroups.contains(c.group))return true
        ensureChannelIndex()
        return (channelByKey[channelKey(c)] ?:emptyList()).any{lockIds.contains(id(channels[it]))}
    }
    private fun channelNumbers():List<Int>{ensureChannelIndex();return liveChannelRepresentatives}
    // Count logical channels, not multiple stream URLs belonging to the same channel.
    private fun actualChannelCount():Int=channelNumbers().size
    private fun numberFor(index:Int):Int{ensureChannelIndex();return channels.getOrNull(index)?.let{channelPosition[channelKey(it)]} ?:0}
    private val manuallyHiddenGroups get()=prefs.getStringSet("manually_hidden_groups",emptySet()) ?: emptySet()
    private fun isGroupHidden(group:String)=manuallyHiddenGroups.contains(group)
    private fun toggleGroupHidden(group:String){
        val updated=manuallyHiddenGroups.toMutableSet()
        if(!updated.add(group))updated.remove(group)
        prefs.edit().putStringSet("manually_hidden_groups",updated).apply()
        Toast.makeText(this,if(isGroupHidden(group))"Group hidden until manually restored" else "Group restored",Toast.LENGTH_SHORT).show()
    }
    private fun groupOptions(group:String){
        showSideDialog(AlertDialog.Builder(this).setTitle(group+" Settings")
            .setItems(arrayOf("Lock / Unlock Group",if(isGroupHidden(group))"Unhide Group" else "Hide Group")){_,choice->
                when(choice){
                    0->toggleLock(group,true)
                    1->{toggleGroupHidden(group);if(active<0)showChannels()}
                }
            }.setNegativeButton("Back",null).create())
    }
    // Keep hidden channels visible in lists for manual playback and long-press unhide.
    // Normal CH+/CH- surfing still excludes hidden channels via surfingIndices().
    private fun visibleIndices()=channelNumbers()
    private fun channelLabel(index:Int):String{
        val c=channels[index]
        val marks=(if(isLocked(c))"🔒 " else "")+(if(isSkipped(c))"🙈 " else "")+(if((channelByKey[channelKey(c)] ?:emptyList()).any{favoriteIds.contains(id(channels[it]))})"★ " else "")
        val hidden=if(isManuallyHidden(c) || isGroupHidden(c.group))"🙈 HIDDEN · " else ""
        return hidden+marks+numberFor(index)+". "+c.name
    }
    private fun searchChannels(){
        val input=EditText(this).apply{hint="Channel name, group or current programme"}
        AlertDialog.Builder(this).setTitle("Search channels").setView(input)
            .setPositiveButton("Search"){_,_->
                val q=input.text.toString().trim()
                if(q.isBlank()){allChannels();return@setPositiveButton}
                val matches=visibleIndices().filter{i->
                    val c=channels[i]
                    c.name.contains(q,true) || c.group.contains(q,true) ||
                        programmeList(c).any{it.title.contains(q,true)}
                }
                channelCollection("Search: "+q,matches)
            }.setNegativeButton("Cancel",null).show()
    }
    private fun rememberRecentChannel(channel:Channel){
        val key=channelKey(channel)
        val recent=JSONArray(prefs.getString(viewerKey("recent_channels"),"[]"))
        val updated=JSONArray().put(key)
        for(i in 0 until recent.length()){
            val value=recent.optString(i)
            if(value!=key && updated.length()<20)updated.put(value)
        }
        prefs.edit().putString(viewerKey("recent_channels"),updated.toString()).apply()
    }
    private fun recentlyWatched(){
        val saved=JSONArray(prefs.getString("recent_channels","[]"))
        val keys=(0 until saved.length()).map{saved.optString(it)}
        val indices=keys.mapNotNull{key->
            visibleIndices().firstOrNull{channelKey(channels[it])==key}
        }
        channelCollection("Recently watched",indices)
    }
    private fun allChannels(){
        prefs.edit().putBoolean("favorites_surfing",false).apply()
        if(channels.isEmpty()){
            channelCollection("All channels",emptyList())
            if(playlists().any{it.enabled})refresh({channelCollection("All channels",visibleIndices())})
            return
        }
        channelCollection("All channels",visibleIndices())
    }
    private val defaultEpgUrl="https://avkb.short.gy/epg.xml.gz"
    private fun configuredEpgUrl():String=prefs.getString("epg_xmltv_url",null)?.trim()?.takeIf{it.isNotEmpty()} ?:defaultEpgUrl
    private fun epgUrls():List<String>{
        val saved=prefs.getString("epg_xmltv_urls",null)
        val legacy=prefs.getString("epg_xmltv_url",null)
        val value=saved ?: legacy?.takeIf{it.isNotBlank()} ?:defaultEpgUrl
        return value.lineSequence().map{it.trim()}.filter{it.startsWith("https://") || it.startsWith("http://")}.distinct().toList()
    }
    private fun configureEpg(){
        val input=EditText(this).apply{
            hint="One XMLTV URL per line"
            setSingleLine(false)
            minLines=4
            maxLines=10
            setText(epgUrls().joinToString("\n"))
        }
        showSideDialog(AlertDialog.Builder(this).setTitle("EPG sources (priority order)")
            .setMessage("One .xml or .xml.gz URL per line. First source has priority. Remove a line to disable that source.")
            .setView(input).setPositiveButton("Save"){_,_->
                val urls=input.text.toString().lineSequence().map{it.trim()}.filter{it.isNotBlank()}.distinct().toList()
                if(urls.any{!it.startsWith("https://") && !it.startsWith("http://")}){
                    Toast.makeText(this,"Use HTTP or HTTPS EPG URLs",Toast.LENGTH_LONG).show()
                }else{
                    prefs.edit().putString("epg_xmltv_urls",urls.joinToString("\n")).apply()
                    epgGuide=emptyMap();epgLoadedAt=0L
                    showEpg()
                }
            }.setNegativeButton("Cancel",null).create())
    }
    private var epgTuneTask:Runnable?=null
    private var appInForeground=false
    private fun scheduleEpgTune(channel:Channel,programme:EpgProgramme,mode:String="auto"){
        val now=System.currentTimeMillis()
        if(programme.start<=now){
            val index=channels.indexOfFirst{sameChannel(it,channel)}
            if(index>=0)playIndex(preferredChannel(index))
            return
        }
        val saved=JSONArray(prefs.getString("epg_tune_schedules","[]"))
        val key=channelKey(channel)+"|"+programme.start
        if((0 until saved.length()).any{saved.optJSONObject(it)?.optString("key")==key}){
            Toast.makeText(this,"Programme already scheduled",Toast.LENGTH_SHORT).show()
            return
        }
        saved.put(JSONObject().put("key",key).put("url",channel.url)
            .put("start",programme.start).put("title",programme.title)
            .put("mode",mode).put("channel_key",channelKey(channel)))
        prefs.edit().putString("epg_tune_schedules",saved.toString()).apply()
        armEpgTune()
        Toast.makeText(this,(if(mode=="auto")"Auto-switch" else "Reminder")+" scheduled for "+programmeTime(programme.start)+" • "+programme.title,Toast.LENGTH_LONG).show()
    }
    private fun armEpgTune(){
        epgTuneTask?.let{handler.removeCallbacks(it)}
        epgTuneTask=null
        val saved=JSONArray(prefs.getString("epg_tune_schedules","[]"))
        val now=System.currentTimeMillis()
        val upcoming=(0 until saved.length()).mapNotNull{saved.optJSONObject(it)}
            .filter{it.optLong("start")>now-120000L}
            .minByOrNull{it.optLong("start")} ?:return
        val delay=(upcoming.optLong("start")-now).coerceAtLeast(0L).coerceAtMost(60000L)
        val task=Runnable{checkEpgTune()}
        epgTuneTask=task
        handler.postDelayed(task,delay)
    }
    private fun checkEpgTune(){
        val saved=JSONArray(prefs.getString("epg_tune_schedules","[]"))
        val now=System.currentTimeMillis()
        val keep=JSONArray()
        val due=mutableListOf<JSONObject>()
        for(i in 0 until saved.length()){
            val entry=saved.optJSONObject(i) ?:continue
            val start=entry.optLong("start")
            if(start<=now && start>=now-120000L)due.add(entry)
            else if(start>now)keep.put(entry)
        }
        prefs.edit().putString("epg_tune_schedules",keep.toString()).apply()
        // If several programmes begin together, use the most recently added
        // one. Resolve by logical channel identity first, then original URL.
        val entry=due.lastOrNull()
        if(entry!=null){
            val key=entry.optString("channel_key")
            val url=entry.optString("url")
            val index=channels.indexOfFirst{channelKey(it)==key}.takeIf{it>=0}
                ?:channels.indexOfFirst{it.url==url}
            if(index>=0){
                val channelName=channels[index].name
                val target=preferredChannel(index)
                if(entry.optString("mode","reminder")=="auto"){
                    // Auto-switch only when HomeTV is active; do not start
                    // playback in the background or bypass channel locks.
                    if(appInForeground && !isFinishing && !isDestroyed){
                        playIndex(target)
                    }
                }else{
                    showSideDialog(AlertDialog.Builder(this)
                        .setTitle("Programme reminder")
                        .setMessage("The scheduled programme on "+channelName+" is starting. Switch to this channel?")
                        .setPositiveButton("Watch"){_,_->
                            playIndex(target)
                        }
                        .setNegativeButton("Stay here",null).create())
                }
            }
        }
        armEpgTune()
    }
    private fun showEpgSchedules(){
        val saved=JSONArray(prefs.getString("epg_tune_schedules","[]"))
        val items=(0 until saved.length()).mapNotNull{saved.optJSONObject(it)}
            .filter{it.optLong("start")>System.currentTimeMillis()}
        if(items.isEmpty()){
            Toast.makeText(this,"No scheduled programmes",Toast.LENGTH_SHORT).show()
            return
        }
        showSideDialog(AlertDialog.Builder(this).setTitle("Scheduled programmes • Select to cancel")
            .setItems(items.map{programmeTime(it.optLong("start"))+"  "+it.optString("title")+"  ["+
                (if(it.optString("mode","reminder")=="auto")"Auto-switch]" else "Reminder]")}.toTypedArray()){_,position->
                val key=items[position].optString("key")
                val remaining=JSONArray()
                for(i in 0 until saved.length()){
                    val item=saved.optJSONObject(i) ?:continue
                    if(item.optString("key")!=key)remaining.put(item)
                }
                prefs.edit().putString("epg_tune_schedules",remaining.toString()).apply()
                armEpgTune()
                Toast.makeText(this,"Programme reminder cancelled",Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Close",null).create())
    }
    private data class EpgProgramme(val start:Long,val end:Long,val title:String,val description:String)
    @Volatile private var epgGuide:Map<String,List<EpgProgramme>> = emptyMap()
    @Volatile private var epgLoadedAt=0L
    private fun epgTime(value:String?):Long{
        if(value.isNullOrBlank())return 0L
        val m=Regex("""^(\d{14})(?:\s*([+-]\d{4}))?""").find(value.trim()) ?:return 0L
        return try{
            val format=java.text.SimpleDateFormat("yyyyMMddHHmmss",java.util.Locale.US)
            format.isLenient=false
            format.timeZone=if(m.groupValues[2].isNotEmpty())
                java.util.TimeZone.getTimeZone("GMT"+m.groupValues[2].substring(0,3)+":"+m.groupValues[2].substring(3))
                else java.util.TimeZone.getDefault()
            format.parse(m.groupValues[1])?.time ?:0L
        }catch(_:Exception){0L}
    }
    private fun programmeList(c:Channel):List<EpgProgramme> {
        val id=c.epgId.trim().lowercase(java.util.Locale.ROOT)
        val name=c.name.trim().lowercase(java.util.Locale.ROOT)
        val raw=epgGuide[id]?.takeIf{it.isNotEmpty()} ?:epgGuide[name] ?:emptyList()
        val global=prefs.getInt("epg_offset_minutes",0)
        val source=prefs.getInt("epg_offset_source_"+c.source.hashCode(),0)
        val shift=(global+source).coerceIn(-1440,1440)*60000L
        return if(shift==0L)raw else raw.map{it.copy(start=it.start+shift,end=it.end+shift)}
    }
    // On-demand only. Downloads at most 3 MB; no continuous network monitoring.
    private fun runNetworkDiagnostics(){
        val dialog=AlertDialog.Builder(this).setTitle("Network speed diagnostics")
            .setMessage("Measuring download speed and latency…")
            .setNegativeButton("Close",null).create()
        dialog.show()
        thread {
            var result:String
            try {
                val start=android.os.SystemClock.elapsedRealtime()
                val connection=(URL("https://speed.cloudflare.com/__down?bytes=3000000")
                    .openConnection() as java.net.HttpURLConnection)
                connection.connectTimeout=8000
                connection.readTimeout=10000
                connection.setRequestProperty("Accept-Encoding","identity")
                connection.instanceFollowRedirects=false
                try{
                    connection.connect()
                    val firstByte=android.os.SystemClock.elapsedRealtime()
                    if(connection.responseCode !in 200..299)throw IllegalStateException("Test server returned "+connection.responseCode)
                    val data=ByteArray(16384)
                    var total=0L
                    connection.inputStream.use{stream->
                        while(total<3000000L){
                            val read=stream.read(data,0,minOf(data.size,(3000000L-total).toInt()))
                            if(read<0)break
                            total+=read
                        }
                    }
                    val elapsed=(android.os.SystemClock.elapsedRealtime()-start).coerceAtLeast(1L)
                    val mbps=total*8.0/elapsed/1000.0
                    result="Approx. download: "+String.format(java.util.Locale.US,"%.1f Mbps",mbps)+
                        "\nTime to first byte: "+(firstByte-start)+" ms"+
                        "\nDownloaded: "+(total/1024)+" KB"+
                        "\nTest duration: "+elapsed+" ms"+
                        "\nResults are estimates and may differ from IPTV server performance."
                }finally{connection.disconnect()}
            }catch(e:Exception){result="Network test failed: "+(e.message?:"Connection unavailable")}
            runOnUiThread{
                if(!isFinishing && !isDestroyed && dialog.isShowing){
                    dialog.setMessage(result)
                }
            }
        }
    }
    private fun configureEpgOffset(){
        val sources=playlists().map{it.name to it.url}
        val choices=(listOf("All playlists (global)")+sources.map{it.first}).toTypedArray()
        AlertDialog.Builder(this).setTitle("EPG time correction")
            .setItems(choices){_,index->
                val key=if(index==0)"epg_offset_minutes" else "epg_offset_source_"+sources[index-1].first.hashCode()
                val values=(-12..12).map{it*30}
                val labels=values.map{v->if(v==0)"No correction" else (if(v>0)"+" else "")+v+" minutes"}.toTypedArray()
                val current=prefs.getInt(key,0)
                AlertDialog.Builder(this).setTitle("Adjust EPG programme times")
                    .setSingleChoiceItems(labels,values.indexOf(current)){dialog,which->
                        prefs.edit().putInt(key,values[which]).apply()
                        dialog.dismiss();settings()
                    }.setNegativeButton("Cancel",null).show()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun programmeTime(t:Long):String =
        java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(java.util.Date(t))
    private fun programmeSummary(c:Channel):String{
        val now=System.currentTimeMillis()
        val entries=programmeList(c)
        val current=entries.firstOrNull{it.start<=now && it.end>now}
        val next=entries.firstOrNull{it.start>now}
        return buildString{
            if(current!=null){
                append("NOW  ").append(programmeTime(current.start)).append("–")
                    .append(programmeTime(current.end)).append("  ").append(current.title)
                if(current.description.isNotBlank())append("\n").append(current.description.take(130))
            }else append("EPG: No current programme")
            if(next!=null)append("\nNEXT  ").append(programmeTime(next.start)).append("  ").append(next.title)
        }
    }
    private fun loadEpg(done:(Boolean)->Unit){
        val urls=epgUrls()
        if(urls.isEmpty()){done(false);return}
        if(epgGuide.isNotEmpty() && System.currentTimeMillis()-epgLoadedAt<60*60*1000L){
            done(true);return
        }
        thread{
            val combined=mutableMapOf<String,MutableMap<Long,EpgProgramme>>()
            var loaded=0
            for(url in urls){
                try{
                    val connection=URL(url).openConnection().apply{
                        connectTimeout=8000;readTimeout=20000;useCaches=false
                    }
                    val raw=connection.getInputStream()
                    val buffered=java.io.BufferedInputStream(raw)
                    buffered.mark(4)
                    val magic0=buffered.read()
                    val magic1=buffered.read()
                    buffered.reset()
                    // Some XMLTV providers serve gzip without a .gz suffix.
                    val stream=if(magic0==0x1f && magic1==0x8b)
                        java.util.zip.GZIPInputStream(buffered) else buffered
                    stream.use{data->
                        val parser=android.util.Xml.newPullParser()
                        parser.setInput(data,"UTF-8")
                        var channel="";var start=0L;var end=0L;var title="";var description=""
                        var event=parser.eventType
                        while(event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT){
                            if(event==org.xmlpull.v1.XmlPullParser.START_TAG){
                                when(parser.name){
                                    "programme"->{
                                        channel=parser.getAttributeValue(null,"channel")?.trim()?.lowercase(java.util.Locale.ROOT).orEmpty()
                                        start=epgTime(parser.getAttributeValue(null,"start"))
                                        end=epgTime(parser.getAttributeValue(null,"stop"))
                                        title="";description=""
                                    }
                                    "title"->if(channel.isNotEmpty())title=parser.nextText()
                                    "desc"->if(channel.isNotEmpty())description=parser.nextText()
                                }
                            }else if(event==org.xmlpull.v1.XmlPullParser.END_TAG && parser.name=="programme"){
                                if(channel.isNotEmpty() && start>0 && end>start && title.isNotBlank() &&
                                    end>System.currentTimeMillis()-2L*86400000L &&
                                    start<System.currentTimeMillis()+8L*86400000L){
                                    // Bound each channel to 1000 programmes and
                                    // keep the first source's entries by priority.
                                    val programmes=combined.getOrPut(channel){mutableMapOf()}
                                    if(programmes.size<1000)programmes.putIfAbsent(
                                        start,EpgProgramme(start,end,title,description.take(2000)))
                                }
                                channel=""
                            }
                            event=parser.next()
                        }
                    }
                    loaded++
                }catch(e:Exception){
                    android.util.Log.w("HomeTV","EPG source failed: "+url,e)
                }
            }
            if(loaded>0){
                epgGuide=combined.mapValues{(_,v)->v.values.sortedBy{it.start}}
                epgLoadedAt=System.currentTimeMillis()
            }
            runOnUiThread{done(loaded>0 || epgGuide.isNotEmpty())}
        }
    }
    private fun showChannelEpg(i:Int){
        val c=channels.getOrNull(i) ?:return
        val now=System.currentTimeMillis()
        val entries=programmeList(c).filter{it.end>now-3600000L}.take(80)
        if(entries.isEmpty()){
            showSideDialog(AlertDialog.Builder(this).setTitle(c.name+" • TV Guide")
                .setMessage("No matching EPG programmes. Check this channel's tvg-id and your EPG sources.")
                .setPositiveButton("Close",null).create())
            return
        }
        val lines=entries.map{p->
            val status=if(p.start<=now && p.end>now)"▶ NOW  " else ""
            status+programmeTime(p.start)+"–"+programmeTime(p.end)+"  "+p.title+
                if(p.description.isNotBlank())"\n"+p.description.take(140) else ""
        }
        showSideDialog(AlertDialog.Builder(this).setTitle(c.name+" • Programme schedule")
            .setItems(lines.toTypedArray()){_,selected->
                val programme=entries[selected]
                showSideDialog(AlertDialog.Builder(this)
                    .setTitle(programme.title)
                    .setMessage(programmeTime(programme.start)+"–"+programmeTime(programme.end)+"\n"+programme.description)
                    .setPositiveButton(if(programme.start<=System.currentTimeMillis())"Watch now" else "Auto-switch"){_,_->scheduleEpgTune(c,programme,"auto")}
                    .setNeutralButton(if(programme.start<=System.currentTimeMillis())"Guide" else "Remind only"){_,_->
                        if(programme.start>System.currentTimeMillis())scheduleEpgTune(c,programme,"reminder")
                    }
                    .setNegativeButton("Cancel",null).create())
            }
            .setPositiveButton("Watch channel"){_,_->playIndex(preferredChannel(i))}
            .setNegativeButton("Close",null).create())
    }
    private fun openCurrentChannelEpg(){
        // The channel-info EPG toggle also disables the automatic OK-key EPG popup.
        if(!prefs.getBoolean("show_channel_epg",true)){
            if(active in channels.indices)showChannelInfo(active)
            return
        }
        val i=active
        if(i !in channels.indices)return
        loadEpg{success->if(success)showChannelEpg(i) else
            showSideDialog(AlertDialog.Builder(this).setTitle("EPG unavailable")
                .setMessage("Could not load your EPG sources.")
                .setPositiveButton("EPG sources"){_,_->configureEpg()}
                .setNegativeButton("Close",null).create())}
    }
    // Half-hour programme grid: the same guide can be browsed while live TV
    // continues playing. A virtualized ListView avoids creating thousands of rows.
    private fun showEpgGrid(startTime:Long=System.currentTimeMillis()){
        val selectedTime=(startTime/1800000L)*1800000L
        val indices=visibleIndices()
        val dialog=AlertDialog.Builder(this).create()
        val layout=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(12,12,12,12)
            setBackgroundColor(0xFF101E31.toInt())
        }
        val header=TextView(this).apply{
            text="TV GUIDE  •  "+java.text.SimpleDateFormat("EEE dd MMM, HH:mm",java.util.Locale.getDefault())
                .format(java.util.Date(selectedTime))
            textSize=20f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(10,8,10,12)
        }
        layout.addView(header)
        val list=ListView(this).apply{
            dividerHeight=1
            isFocusable=true
        }
        val matching=indices.filter{programmeList(channels[it]).isNotEmpty()}
        val adapter=object:android.widget.BaseAdapter(){
            override fun getCount()=matching.size
            override fun getItem(position:Int):Any=matching[position]
            override fun getItemId(position:Int)=matching[position].toLong()
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View{
                val row=(convertView as? TextView) ?:TextView(this@MainActivity).apply{
                    textSize=17f
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(18,15,14,15)
                    maxLines=3
                    ellipsize=android.text.TextUtils.TruncateAt.END
                }
                val i=matching[position]
                val c=channels[i]
                val programme=programmeList(c).firstOrNull{it.start<=selectedTime && it.end>selectedTime}
                val next=programmeList(c).firstOrNull{it.start>selectedTime}
                row.text=numberFor(i).toString()+". "+c.name+"\n"+
                    (programme?.let{programmeTime(it.start)+"–"+programmeTime(it.end)+"  "+it.title}
                        ?:next?.let{"Next "+programmeTime(it.start)+"  "+it.title}
                        ?:"No programme at this time")
                return row
            }
        }
        list.adapter=adapter
        layout.addView(list,LinearLayout.LayoutParams(-1,
            (resources.displayMetrics.heightPixels*0.55f).toInt().coerceAtLeast(260)))
        list.setOnItemClickListener{_,_,position,_->
            val i=matching[position]
            dialog.dismiss()
            showChannelEpg(i)
        }
        val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        fun navButton(title:String,weight:Float=1f,action:()->Unit){
            nav.addView(button(title){dialog.dismiss();action()},LinearLayout.LayoutParams(0,-2,weight))
        }
        navButton("◀ 30m"){showEpgGrid(selectedTime-1800000L)}
        navButton("Now"){showEpgGrid(System.currentTimeMillis())}
        navButton("30m ▶"){showEpgGrid(selectedTime+1800000L)}
        layout.addView(nav)
        val footer=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        footer.addView(button("Scheduled"){dialog.dismiss();showEpgSchedules()},LinearLayout.LayoutParams(0,-2,1f))
        footer.addView(button("Sources"){dialog.dismiss();configureEpg()},LinearLayout.LayoutParams(0,-2,1f))
        footer.addView(button("Close"){dialog.dismiss()},LinearLayout.LayoutParams(0,-2,1f))
        layout.addView(footer)
        if(matching.isEmpty()){
            header.text="No matching EPG channels — check tvg-id and EPG sources"
        }
        dialog.setView(layout)
        showSideDialog(dialog)
    }
    private fun showEpg(){
        if(epgUrls().isEmpty()){configureEpg();return}
        Toast.makeText(this,"Loading TV guide…",Toast.LENGTH_SHORT).show()
        loadEpg{success->
            if(!success){
                showSideDialog(AlertDialog.Builder(this).setTitle("EPG unavailable")
                    .setMessage("Unable to load XMLTV guide.")
                    .setPositiveButton("Change EPG URL"){_,_->configureEpg()}
                    .setNegativeButton("Close",null).create())
            }else showEpgGrid()
        }
    }
    private val manuallyHiddenIds get()=prefs.getStringSet("manually_hidden_channels",emptySet()) ?: emptySet()
    // Persist a channel identity rather than its stream URL. Playlist refreshes and
    // fallback to alternative URLs must never restore a manually hidden channel.
    private fun hiddenChannelId(c:Channel)="channel:"+channelKey(c)
    private fun isManuallyHidden(c:Channel):Boolean{
        val saved=manuallyHiddenIds
        if(saved.contains(hiddenChannelId(c)))return true
        // Honor existing installations which stored URL-based hidden entries.
        ensureChannelIndex()
        return (channelByKey[channelKey(c)] ?:emptyList()).any{saved.contains(id(channels[it]))}
    }
    private fun toggleManualHide(c:Channel){
        val updated=manuallyHiddenIds.toMutableSet()
        val shouldHide=!isManuallyHidden(c)
        // Remove obsolete URL-based entries for all alternatives when restoring.
        ensureChannelIndex()
        (channelByKey[channelKey(c)] ?:emptyList()).forEach{updated.remove(id(channels[it]))}
        if(shouldHide)updated.add(hiddenChannelId(c)) else updated.remove(hiddenChannelId(c))
        prefs.edit().putStringSet("manually_hidden_channels",updated).apply()
        Toast.makeText(this,if(shouldHide)"Channel hidden until manually restored" else "Channel restored",Toast.LENGTH_SHORT).show()
    }
    private fun checkHiddenChannelsNow(){
        val hidden=channelNumbers().filter{
            isManuallyHidden(channels[it]) || isGroupHidden(channels[it].group) ||
                temporaryFailureHidden.contains(channelKey(channels[it]))
        }.distinctBy{channelKey(channels[it])}
        if(hidden.isEmpty()){
            Toast.makeText(this,"No hidden channels to check",Toast.LENGTH_SHORT).show()
            return
        }
        // Run quietly; only display a full-screen results menu after checking finishes.
        // Do not put a modal progress window over the currently playing channel.
        Toast.makeText(this,"Checking "+hidden.size+" hidden channels in background…",Toast.LENGTH_SHORT).show()
        thread(name="HomeTV-hidden-channel-check"){
            val reachableIndices=mutableListOf<Int>()
            hidden.forEach{index->
                val c=channels[index]
                val urls=alternateIndices(index).map{channels[it]}.ifEmpty{listOf(c)}
                val reachable=urls.any{candidate->
                    val address=candidate.url.lowercase(java.util.Locale.ROOT)
                    if(!address.startsWith("http://") && !address.startsWith("https://"))return@any false
                    if("youtube.com" in address || "youtu.be" in address)return@any false
                    try{
                        val connection=URL(candidate.url).openConnection() as java.net.HttpURLConnection
                        try{
                            connection.connectTimeout=3500
                            connection.readTimeout=3500
                            connection.instanceFollowRedirects=true
                            connection.setRequestProperty("Range","bytes=0-1023")
                            connection.setRequestProperty("User-Agent",candidate.headers["User-Agent"] ?: "HomeTV/1.0 AndroidTV")
                            candidate.headers.forEach{(key,value)->
                                if(!key.equals("User-Agent",true))connection.setRequestProperty(key,value)
                            }
                            val code=connection.responseCode
                            val contentType=connection.contentType?.lowercase(java.util.Locale.ROOT) ?: ""
                            code in 200..299 && !contentType.contains("text/html")
                        }finally{connection.disconnect()}
                    }catch(_:Exception){false}
                }
                if(reachable)reachableIndices.add(index)
            }
            handler.post{
                if(reachableIndices.isEmpty()){
                    Toast.makeText(this,"Hidden channel check complete • No reachable channels found",Toast.LENGTH_LONG).show()
                    return@post
                }
                val results=reachableIndices.toMutableList()
                val labels=results.map{channels[it].name+"  —  Unhide"}.toMutableList()
                val adapter=android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels)
                val dialog=AlertDialog.Builder(this)
                    .setTitle("Hidden channel check complete • "+results.size+" found")
                    .setAdapter(adapter){_,_->}
                    .setNegativeButton("Close",null).create()
                dialog.setOnShowListener{
                    dialog.listView?.setOnItemClickListener{_,_,position,_->
                        val index=results.getOrNull(position) ?:return@setOnItemClickListener
                        val c=channels[index]
                        AlertDialog.Builder(this).setTitle(c.name)
                            .setMessage("The stream URL responded successfully, but video playback is not verified.")
                            .setPositiveButton("Unhide"){_,_->
                                if(isManuallyHidden(c))toggleManualHide(c)
                                temporaryFailureHidden.remove(channelKey(c))
                                unavailableChannels.remove(channelKey(c))
                                if(isGroupHidden(c.group))
                                    Toast.makeText(this,"Restore the hidden group separately.",Toast.LENGTH_LONG).show()
                                results.removeAt(position)
                                labels.removeAt(position)
                                adapter.notifyDataSetChanged()
                                dialog.setTitle("Reachable hidden channels • "+results.size+" remaining")
                            }
                            .setNegativeButton("Keep hidden",null).show()
                    }
                }
                showSideDialog(dialog)
            }
        }
    }
    private fun hiddenChannels(){
        val hidden=channelNumbers().filter{isManuallyHidden(channels[it]) || isGroupHidden(channels[it].group)}
        channelCollection("Hidden channels",hidden)
        if(manuallyHiddenGroups.isNotEmpty()){
            root.addView(button("Restore hidden group"){
                val groups=manuallyHiddenGroups.toList().sorted()
                showSideDialog(AlertDialog.Builder(this).setTitle("Unhide Group")
                    .setItems(groups.toTypedArray()){_,position->toggleGroupHidden(groups[position]);hiddenChannels()}
                    .setNegativeButton("Back",null).create())
            })
        }
    }
    private fun favorites(){
        prefs.edit().putBoolean("favorites_surfing",true).apply()
        channelCollection("Favorites",channels.indices.filter{!isManuallyHidden(channels[it]) && !isGroupHidden(channels[it].group) && favoriteIds.contains(id(channels[it]))})
    }
    // User-defined ordering is stored independently for Favorites and each channel list.
    // The stable source + channel + URL identifier avoids mixing different playlists.
    private fun orderedChannelIndices(title:String,raw:List<Int>):List<Int>{
        val distinct=raw.filter{it in channels.indices}.distinctBy{id(channels[it])}
        val key=if(title=="Favorites")"channel_order_favorites" else "channel_order_all"
        val saved=prefs.getString(key,"") ?: ""
        if(saved.isBlank())return distinct
        val positions=try{
            val array=JSONArray(saved)
            (0 until array.length()).associate{array.optString(it) to it}
        }catch(_:Exception){emptyMap<String,Int>()}
        return distinct.withIndex().sortedWith(compareBy<IndexedValue<Int>>{
            positions[id(channels[it.value])] ?: Int.MAX_VALUE
        }.thenBy{it.index}).map{it.value}
    }
    private fun moveChannelInList(title:String,items:List<Int>,position:Int,direction:Int){
        val destination=position+direction
        if(destination !in items.indices)return
        val reordered=items.toMutableList()
        val moved=reordered.removeAt(position)
        reordered.add(destination,moved)
        val key=if(title=="Favorites")"channel_order_favorites" else "channel_order_all"
        val array=JSONArray()
        reordered.forEach{array.put(id(channels[it]))}
        prefs.edit().putString(key,array.toString()).apply()
        channelCollection(title,reordered)
    }
    // Long press in channel lists: quick actions only. Never open the right-key settings.
    private fun showChannelListQuickActions(index:Int,refresh:()->Unit){
        if(index !in channels.indices)return
        val c=channels[index]
        val favorite=channels.any{sameChannel(it,c) && favoriteIds.contains(id(it))}
        val options=arrayOf(
            if(isManuallyHidden(c))"Unhide" else "Hide",
            if(favorite)"Non Favourite" else "Favourite",
            if(isLocked(c))"Unlock" else "Lock"
        )
        showSideDialog(AlertDialog.Builder(this)
            .setTitle(numberFor(index).toString()+". "+c.name)
            .setItems(options){_,selection->
                when(selection){
                    0->{toggleManualHide(c);refresh()}
                    1->{favorite(c);refresh()}
                    2->requirePin {
                        val locked=(prefs.getStringSet("locked_channels",emptySet())?:emptySet()).toMutableSet()
                        val related=channelByKey[channelKey(c)] ?: listOf(index)
                        if(isLocked(c)){
                            related.forEach{locked.remove(id(channels[it]))}
                            if(lockGroups.contains(c.group)){
                                Toast.makeText(this,"This group is locked; unlock the group separately",Toast.LENGTH_LONG).show()
                            }
                        }else locked.add(id(c))
                        prefs.edit().putStringSet("locked_channels",locked).apply()
                        refresh()
                    }
                }
            }.setNegativeButton("Back",null).create())
    }
    private fun channelCollection(title:String,indices:List<Int>){
        val indices=orderedChannelIndices(title,indices)
        reset(title+" ("+indices.size+")")
        val list=ListView(this).apply{
            dividerHeight=1
            isFocusable=true
            isFocusableInTouchMode=true
            setBackgroundColor(0xFF101E31.toInt())
        }
        val names=indices.map{i->
            val c=channels[i]
            channelLabel(i)+"  ·  "+c.group
        }
        list.adapter=object:android.widget.BaseAdapter(){
            override fun getCount()=names.size
            override fun getItem(position:Int):Any=names[position]
            override fun getItemId(position:Int)=indices[position].toLong()
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View{
                val view=(convertView as? TextView) ?: TextView(this@MainActivity).apply{
                    textSize=18f
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(22,17,18,17)
                    minHeight=62
                    isSingleLine=true
                    ellipsize=android.text.TextUtils.TruncateAt.END
                }
                view.text=names[position]
                return view
            }
        }
        list.setOnItemClickListener{_,_,position,_->
            val selected=indices[position]
            // Explicit selection may play a hidden or locked channel.
            // Locked channels still require their PIN in playIndex().
            playIndex(preferredChannel(selected))
        }
        list.setOnItemLongClickListener{_,_,position,_->
            val selected=indices[position]
            showChannelListQuickActions(selected){
                (list.adapter as android.widget.BaseAdapter).notifyDataSetChanged()
                // Preserve the selected channel and the open list.
                list.setSelection(position)
                list.requestFocus()
            }
            true
        }
        if(title=="All channels"){
            val last=prefs.getString("last_url",null)
            val previous=indices.firstOrNull{channels[it].url==last}
            if(previous!=null){
                root.addView(button("▶ Resume last channel: "+channels[previous].name){playIndex(previous)})
                val position=indices.indexOf(previous)
                list.setSelection(position)
                list.post{list.setSelection(position)}
            }
        }
        if(title=="All channels" || title=="Favorites"){
            val playingUrl=channels.getOrNull(active)?.url ?: prefs.getString("last_url",null)
            val position=indices.indexOfFirst{channels[it].url==playingUrl}
            if(position>=0){
                list.choiceMode=android.widget.ListView.CHOICE_MODE_SINGLE
                list.setItemChecked(position,true)
                list.setSelection(position)
                list.post{
                    list.setItemChecked(position,true)
                    list.setSelection(position)
                    list.requestFocus()
                }
            }
        }
        root.addView(label("Long press a channel: Hide / Unhide · Favourite · Lock / Unlock"))
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(button("Back"){showHome()})
    }
    private val bufferProfiles=arrayOf("Auto","Ultra Fast","Fast","Balanced","Stable","Maximum Buffer")
    private val autoBufferLevels=intArrayOf(0,1,2,3)
    private var autoBufferLevel=0
    private var autoBufferLastChange=0L
    private var autoBufferStableSince=0L
    private var autoBufferRecoveryCount=0
    private fun autoBufferEnabled()=bufferProfile()=="Auto"
    private fun bufferLevelValues(level:Int):IntArray=when(level.coerceIn(0,3)){
        0->intArrayOf(2000,15000,1000,2000)
        1->intArrayOf(4000,25000,1500,3500)
        2->intArrayOf(6000,35000,2500,5000)
        else->intArrayOf(8000,45000,3000,6500)
    }
    private fun autoBufferStalled(){
        if(!autoBufferEnabled())return
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-autoBufferLastChange<12000L)return
        autoBufferRecoveryCount++
        if(autoBufferRecoveryCount>=2 && autoBufferLevel<3){
            autoBufferLevel++
            autoBufferLastChange=now
            autoBufferRecoveryCount=0
            android.util.Log.i("HomeTV","Adaptive buffer increased to level "+autoBufferLevel)
        }
    }
    private fun autoBufferHealthy(){
        if(!autoBufferEnabled())return
        val now=android.os.SystemClock.elapsedRealtime()
        if(autoBufferStableSince==0L)autoBufferStableSince=now
        if(now-autoBufferStableSince>15*60*1000L && autoBufferLevel>0){
            autoBufferLevel--
            autoBufferLastChange=now
            autoBufferStableSince=now
        }
        autoBufferRecoveryCount=0
    }
    private fun bufferProfile()=prefs.getString("buffer_profile","Auto") ?: "Auto"
    private fun bufferValues():IntArray=when(bufferProfile()){
        "Auto"->bufferLevelValues(autoBufferLevel)
        "Ultra Fast"->intArrayOf(500,3500,250,500)
        "Fast"->intArrayOf(1000,7000,500,1000)
        "Stable"->intArrayOf(4000,25000,1500,3500)
        "Maximum Buffer"->intArrayOf(8000,45000,3000,6500)
        else->intArrayOf(2000,15000,1000,2000)
    }
    private fun chooseBufferProfile(){
        AlertDialog.Builder(this).setTitle("Playback buffer")
            .setSingleChoiceItems(bufferProfiles,bufferProfiles.indexOf(bufferProfile()).coerceAtLeast(0)){dialog,i->
                prefs.edit().putString("buffer_profile",bufferProfiles[i]).apply()
                dialog.dismiss();if(active>=0)showPlaybackSettings() else settings()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun engineFor(c:Channel):String{
        fallbackEngineOverride?.let{return it}
        prefs.getString("manual_engine_"+channelKey(c),null)?.let{
            if(it=="Media3" || it=="LibVLC" || it=="IJKPlayer")return it
        }
        // Confirmation belongs to a specific URL, not every source of this channel.
        if(prefs.getString("working_url_"+channelKey(c),null)==c.url){
            prefs.getString("confirmed_engine_"+channelKey(c),null)?.let{
                if(it=="Media3" || it=="LibVLC" || it=="IJKPlayer")return it
            }
        }
        val universal=prefs.getString("engine","Auto") ?: "Auto"
        if(prefs.getBoolean("libvlc_only_"+hash(c.url),false))return "LibVLC"
        if(universal=="Media3" || universal=="LibVLC" || universal=="IJKPlayer")return universal
        return "Media3"
    }
    private fun chooseChannelEngine(i:Int){
        if(i !in channels.indices)return
        val c=channels[i]
        val universal=prefs.getString("engine","Media3") ?: "Media3"
        val choices=arrayOf("Auto (Universal: "+universal+")","Media3","LibVLC","IJKPlayer")
        val selected=when(prefs.getString("manual_engine_"+channelKey(c),null)){
            "Media3"->1
            "LibVLC"->2
            "IJKPlayer"->3
            else->0
        }
        AlertDialog.Builder(this).setTitle(c.name+" — Player")
            .setSingleChoiceItems(choices,selected){dialog,which->
                prefs.edit().apply{
                    if(which==0)remove("manual_engine_"+channelKey(c))
                    else putString("manual_engine_"+channelKey(c),choices[which])
                }.apply()
                engineAttempts.clear();fallbackEngineOverride=null;unavailableChannels.remove(channelKey(c))
                dialog.dismiss()
                if(active==i)playIndex(i)
            }.setNegativeButton("Cancel",null).show()
    }
    private fun channelOptions(i:Int){
        if(i !in channels.indices)return
        val c=channels[i]
        val opts=arrayOf(
            "Watch channel",
            "Aspect ratio",
            "Picture quality",
            "Audio tracks",
            "Subtitles",
            "Sleep timer",
            "Playback engine",
            "Alternative stream",
            if(favoriteIds.contains(id(c)))"Remove favorite" else "Add favorite",
            "Lock / Unlock",
            if(isManuallyHidden(c))"Unhide channel" else "Hide channel",
            "All channels",
            "Settings",
            "Exit"
        )
        val dialog=AlertDialog.Builder(this)
            .setTitle(numberFor(i).toString()+". "+c.name+" Settings")
            .setItems(opts){d,v->
                d.dismiss()
                when(v){
                    0->playIndex(preferredChannel(i))
                    1->selectScreenMode(i)
                    2->selectQuality(i)
                    3->audioTracks()
                    4->subtitleTracks(i)
                    5->sleepTimer()
                    6->chooseChannelEngine(i)
                    7->selectAlternative(i)
                    8->{favorite(c);if(active<0)allChannels()}
                    9->toggleLock(id(c),false)
                    10->{toggleManualHide(c);if(active<0)allChannels()}
                    11->allChannels()
                    12->liveSettings()
                    13->confirmExit()
                }
            }
            .setNegativeButton("Back",null).create()
        dialog.setOnShowListener{
            dialog.listView?.apply{
                choiceMode=android.widget.ListView.CHOICE_MODE_NONE
                clearChoices()
                setSelection(0)
                requestFocus()
            }
        }
        showSideDialog(dialog)
    }

    // Android HOME / app switching: never leave a video or WebView playing off-screen.
    // Keep the selected URL and VOD resume position; only restore a session
    // that this Activity itself suspended, after licensing is still valid.
    // Preserve the exact stream variant when different channels share a URL.
    // The suspended identity is transient: no secret or player object is persisted.
    private var suspendedPlaybackUrl:String?=null
    private var suspendedPlaybackIdentity:String?=null
    override fun onResume(){
        super.onResume()
        appInForeground=true
        armEpgTune()
        val url=suspendedPlaybackUrl
        val identity=suspendedPlaybackIdentity
        suspendedPlaybackUrl=null
        suspendedPlaybackIdentity=null
        if(url!=null && licenseReady && channels.isNotEmpty()){
            val index=channels.indexOfFirst{it.url==url && id(it)==identity}
                .takeIf{it>=0} ?:channels.indexOfFirst{it.url==url}
            if(index>=0){
                // Do not bypass parental controls on resume.
                playIndex(index)
            }else showHome()
        }
    }
    override fun onPause(){
        appInForeground=false
        if(active in channels.indices && !isFinishing){
            val current=channels[active]
            suspendedPlaybackUrl=current.url
            suspendedPlaybackIdentity=id(current)
            // stopPlayback saves VOD progress and releases Media3, LibVLC and WebView.
            stopPlayback()
        }
        super.onPause()
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(14,12,14,12) }
        root.setBackgroundColor(0xFF101E31.toInt())
        setContentView(android.widget.ScrollView(this).apply{isFillViewport=true;addView(root)})
        beginLicensedSession()
    }
    private fun label(s:String)=TextView(this).apply { text=s; textSize=23f; setPadding(18,16,18,16); setTextColor(android.graphics.Color.WHITE) }
    private fun button(s:String, action:()->Unit)=Button(this).apply {
        text=s;isAllCaps=false;textSize=17f
        setTextColor(android.graphics.Color.WHITE)
        backgroundTintList=android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf()
            ),
            intArrayOf(0xFFFFB300.toInt(),0xFFFFB300.toInt(),0xFF254466.toInt())
        )
        setOnFocusChangeListener{view,focused->
            (view as Button).setTextColor(if(focused)android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
        setOnClickListener{action()}
    }
    private val navigationHistory=mutableListOf<()->Unit>()
    private var navigatingBack=false
    private var currentMenu:(()->Unit)?=null
    private fun reset(title:String) {
        val destination:(()->Unit)?=when{
            title.startsWith("My Home TV  •")->({showHome()})
            title=="HomeTV Settings"->({settings()})
            else->null
        }
        if(!navigatingBack && destination!=null && currentMenu!=null &&
            (title=="HomeTV Settings" && !settingsScreenVisible)){
            navigationHistory.add(currentMenu!!)
        }
        if(destination!=null)currentMenu=destination
        settingsScreenVisible=false; stopPlayback(); window.decorView.systemUiVisibility=0; setContentView(android.widget.ScrollView(this).apply{isFillViewport=true;addView(root.also{(it.parent as? android.view.ViewGroup)?.removeView(it)})}); root.removeAllViews(); root.addView(label(title)) }
    private fun playlists():MutableList<Playlist> {
        val arr=JSONArray(prefs.getString("playlists","[]"))
        return mutableListOf(Playlist("HomeTV Live",licensedPlaylist)).apply { addAll(MutableList(arr.length()){ val o=arr.getJSONObject(it); Playlist(o.getString("name"),o.getString("url"),o.optBoolean("enabled",true)) }) }
    }
    private fun savePlaylists(list:List<Playlist>) {
        val arr=JSONArray(); list.filter{it.url!=licensedPlaylist}.forEach { arr.put(JSONObject().put("name",it.name).put("url",it.url).put("enabled",it.enabled)) }
        prefs.edit().putString("playlists",arr.toString()).apply()
    }

    // My Home TV media library. Unmarked EXTINF entries are always Live TV.
    private fun mediaItems(type:String)=channels.indices.filter{channels[it].mediaType==type}
    // Bounded image cache: do not start a network thread on every recycled list row.
    private val posterCache=object:android.util.LruCache<String,android.graphics.Bitmap>(48){}
    private val posterLoading=java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private fun loadPoster(image:android.widget.ImageView,url:String){
        image.tag=url
        if(url.isBlank()){image.setImageDrawable(null);return}
        posterCache.get(url)?.let{image.setImageBitmap(it);return}
        image.setImageDrawable(null)
        if(!posterLoading.add(url))return
        thread(name="MyHomeTV-poster"){
            try{
                val conn=URL(url).openConnection().apply{connectTimeout=5000;readTimeout=5000}
                val bmp=conn.getInputStream().use{android.graphics.BitmapFactory.decodeStream(it)}
                if(bmp!=null){
                    posterCache.put(url,bmp)
                    runOnUiThread{if(image.tag==url)image.setImageBitmap(bmp)}
                }
            }catch(_:Exception){}finally{posterLoading.remove(url)}
        }
    }
    private fun mediaSeries(){
        val items=mediaItems("series")
        reset("My Home TV  •  Series")
        root.addView(label("Series  |  "+items.size+" episodes"))
        items.groupBy{channels[it].group}.toSortedMap().forEach{(name,episodes)->
            root.addView(button("▣  "+name+"  •  "+episodes.size+" episodes"){
                val ordered=episodes.sortedWith(compareBy<Int>(
                    {channels[it].season.toIntOrNull()?:0},
                    {channels[it].episode.toIntOrNull()?:0}
                ))
                mediaRows(name,ordered)
            })
        }
        root.addView(button("▶  Continue Watching"){continueMedia()})
        root.addView(button("←  Home"){showHome()})
    }
    private fun mediaMusic(){
        val items=mediaItems("music")
        reset("My Home TV  •  Music")
        root.addView(label("Music  |  "+items.size+" songs"))
        root.addView(button("♫  All Songs"){mediaRows("All Songs",items)})
        items.groupBy{channels[it].artist.ifBlank{"Unknown Artist"}}.toSortedMap().forEach{(artist,songs)->
            root.addView(button("♫  "+artist+"  ("+songs.size+")"){mediaRows(artist,songs)})
        }
        root.addView(button("←  Home"){showHome()})
    }
    private fun mediaPlaybackControls(){
        val c=channels.getOrNull(active)?:return
        if(c.mediaType !in setOf("movie","series","music"))return
        val exo=player
        val vlc=vlcPlayer
        if(exo==null && vlc==null){
            Toast.makeText(this,"This web player controls seeking on its own",Toast.LENGTH_SHORT).show()
            return
        }
        val duration=if(exo!=null)exo.duration else vlc?.length?:0L
        val position=if(exo!=null)exo.currentPosition else vlc?.time?:0L
        if(duration<=0L){Toast.makeText(this,"Seeking unavailable for this stream",Toast.LENGTH_SHORT).show();return}
        val bar=android.widget.SeekBar(this).apply{
            max=(duration/1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            progress=(position/1000L).coerceAtMost(max.toLong()).toInt()
        }
        val info=TextView(this).apply{
            setTextColor(android.graphics.Color.WHITE);textSize=18f
            text="Use LEFT / RIGHT to seek, OK to play from selected position"
            setPadding(20,20,20,20)
        }
        val layout=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(30,20,30,20)
            addView(info);addView(bar)
        }
        AlertDialog.Builder(this).setTitle("▶  "+c.name)
            .setView(layout)
            .setPositiveButton("▶  Play here"){_,_->
                val target=bar.progress.toLong()*1000L
                if(exo!=null)exo.seekTo(target) else vlc?.time=target
            }
            .setNeutralButton(if(exo?.isPlaying==true || vlc?.isPlaying==true) "Pause" else "Play"){_,_->
                if(exo!=null){if(exo.isPlaying)exo.pause() else exo.play()}
                else if(vlc!=null){if(vlc.isPlaying)vlc.pause() else vlc.play()}
            }
            .setNegativeButton("Close",null).show()
    }
    private fun mediaKey(c:Channel)=viewerKey("myhometv_progress_"+c.url.hashCode().toString())
    private fun mediaResumeSeconds(c:Channel):Long=prefs.getLong(mediaKey(c),0L)
    private fun saveCurrentMediaProgress(){
        val c=channels.getOrNull(active)?:return
        if(c.mediaType !in setOf("movie","series","music"))return
        val seconds=when{
            player!=null->(player!!.currentPosition/1000L).coerceAtLeast(0L)
            vlcPlayer!=null->(vlcPlayer!!.time/1000L).coerceAtLeast(0L)
            else->0L
        }
        if(seconds>0L)prefs.edit().putLong(mediaKey(c),seconds).apply()
    }
    private fun continueMedia(){
        val indices=channels.indices.filter{channels[it].mediaType in setOf("movie","series") && mediaResumeSeconds(channels[it])>0}
        mediaRows("Continue Watching",indices)
    }
    private fun mediaLibrary(type:String){
        val title=when(type){"movie"->"Movies";"series"->"Series";"music"->"Music";else->"Web TV"}
        reset("My Home TV  •  "+title)
        val indices=mediaItems(type)
        root.addView(label(title+"  |  "+indices.size+" items"))
        root.addView(button("⌕  Search "+title){mediaSearch(type)})
        if(type=="movie"||type=="series")root.addView(button("▶  Continue Watching"){continueMedia()})
        if(indices.isEmpty())root.addView(label("No items yet. Add media-type=\""+type+"\" to an EXTINF playlist entry."))
        indices.map{channels[it].group}.distinct().sorted().forEach{group->
            root.addView(button("▸  "+group+"  ("+indices.count{channels[it].group==group}+")"){
                mediaRows(group,indices.filter{channels[it].group==group})
            })
        }
        root.addView(button("▦  All "+title){mediaRows(title,indices)})
        root.addView(button("＋  Manage playlists"){managePlaylists()})
        root.addView(button("←  Home"){showHome()})
    }
    private fun mediaSearch(type:String){
        val input=EditText(this).apply{hint="Search title, artist, album or group"}
        AlertDialog.Builder(this).setTitle("Search media").setView(input).setPositiveButton("Search"){_,_->
            val q=input.text.toString()
            mediaRows("Search: "+q,mediaItems(type).filter{
                val c=channels[it]
                c.name.contains(q,true)||c.group.contains(q,true)||
                c.artist.contains(q,true)||c.album.contains(q,true)
            })
        }.setNegativeButton("Cancel",null).show()
    }
    private fun mediaRows(title:String,indices:List<Int>){
        reset("My Home TV  •  "+title)
        root.addView(label(indices.size.toString()+" items"))
        val list=ListView(this).apply{
            dividerHeight=2
            isFocusable=true
            setBackgroundColor(0xFF101E31.toInt())
        }
        list.adapter=object:android.widget.BaseAdapter(){
            override fun getCount()=indices.size
            override fun getItem(position:Int):Any=channels[indices[position]]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View{
                val row=(convertView as? LinearLayout)?:LinearLayout(this@MainActivity).apply{
                    orientation=LinearLayout.HORIZONTAL
                    setPadding(16,8,16,8)
                    addView(android.widget.ImageView(this@MainActivity).apply{
                        layoutParams=LinearLayout.LayoutParams(108,86)
                        scaleType=android.widget.ImageView.ScaleType.CENTER_CROP
                    })
                    addView(TextView(this@MainActivity).apply{
                        textSize=18f
                        setTextColor(android.graphics.Color.WHITE)
                        setPadding(20,12,8,12)
                    },LinearLayout.LayoutParams(0,-2,1f))
                }
                val c=channels[indices[position]]
                val progress=mediaResumeSeconds(c)
                (row.getChildAt(1) as TextView).text=c.name+
                    (if(c.artist.isNotBlank())"\n"+c.artist else "")+
                    (if(c.season.isNotBlank())"  S"+c.season+" E"+c.episode else "")+
                    (if(progress>0)"\n▶ Resume at "+(progress/60)+"m "+(progress%60)+"s" else "")
                val image=row.getChildAt(0) as android.widget.ImageView
                loadPoster(image,c.logo.ifBlank{youtubeThumbnail(c.url)})
                return row
            }
        }
        list.setOnItemClickListener{_,_,position,_->mediaDetails(indices[position])}
        root.addView(list,LinearLayout.LayoutParams(-1,(resources.displayMetrics.heightPixels*0.70).toInt()))
        root.addView(button("←  Home"){showHome()})
    }
    private fun youtubeThumbnail(url:String):String{
        val uri=try{Uri.parse(url)}catch(_:Exception){return ""}
        val host=uri.host?.lowercase()?.removePrefix("www.")?:""
        val id=when(host){
            "youtu.be"->uri.path?.trim('/')?.substringBefore('/')
            "youtube.com","m.youtube.com"->uri.getQueryParameter("v")
            else->null
        }
        return if(id!=null && id.matches(Regex("[A-Za-z0-9_-]{11}")))
            "https://i.ytimg.com/vi/"+id+"/hqdefault.jpg" else ""
    }
    private var requestedMediaResumeSeconds=0L
    private fun mediaDetails(index:Int){
        val c=channels.getOrNull(index)?:return
        val position=mediaResumeSeconds(c)
        val details=listOf(c.group,c.genre,c.year,c.artist,c.album,c.description)
            .filter{it.isNotBlank()}.joinToString(" • ")
        val layout=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(28,20,28,20)
        }
        if(details.isNotBlank()){
            layout.addView(TextView(this).apply{
                text=details
                textSize=16f
                setPadding(0,0,0,20)
            })
        }
        var mediaDialog:AlertDialog?=null
        val play=android.widget.Button(this).apply{
            text=if(c.mediaType=="web") "▶  Open Web TV" else "▶  Play from beginning"
            isFocusable=true
            setOnClickListener{mediaDialog?.dismiss();requestedMediaResumeSeconds=0L;prefs.edit().remove(mediaKey(c)).apply();playIndex(index)}
        }
        layout.addView(play)
        if(position>0L){
            layout.addView(android.widget.Button(this).apply{
                text="↻  Resume from "+(position/60)+"m "+(position%60)+"s"
                setOnClickListener{mediaDialog?.dismiss();requestedMediaResumeSeconds=position;playIndex(index)}
            })
        }
        layout.addView(android.widget.Button(this).apply{
            text="★  Add / remove favorite"
            setOnClickListener{favorite(c)}
        })
        layout.addView(android.widget.Button(this).apply{
            text="Clear saved progress"
            setOnClickListener{
                prefs.edit().remove(mediaKey(c)).apply()
                Toast.makeText(this@MainActivity,"Progress cleared",Toast.LENGTH_SHORT).show()
            }
        })
        val dialog=AlertDialog.Builder(this).setTitle(c.name).setView(layout)
            .setNegativeButton("Close",null).create()
        mediaDialog=dialog
        dialog.setOnShowListener{play.requestFocus()}
        dialog.show()
    }

    private fun showHome() {
        reset("My Home TV  •  HOME")
        root.addView(label("YOUR TELEVISION  |  "+actualChannelCount()+" LIVE CHANNELS"))
        root.addView(button("▣  Movies"){mediaLibrary("movie")})
        root.addView(button("▤  Series"){mediaSeries()})
        root.addView(button("♫  Music"){mediaMusic()})
        root.addView(button("◉  Web TV"){mediaLibrary("web")})
        root.addView(button("▶  Continue watching movies & series"){continueMedia()})
        root.addView(button("▶  Continue watching"){if(channels.isEmpty())refresh({resumeLast()})else resumeLast()})
        root.addView(label("DISCOVER"))
        root.addView(button("▦  All live channels"){allChannels()})
        root.addView(button("★  My favorites"){favorites()})
        root.addView(button("⌕  Search channels and programmes"){searchChannels()})
        root.addView(button("◷  Recently watched"){recentlyWatched()})
        root.addView(button("🙈  Hidden channels"){hiddenChannels()})
        root.addView(button("▤  TV programme guide (EPG)"){showEpg()})
        root.addView(button("☷  Browse by category"){showChannels()})
        root.addView(label("LIBRARY & CONTROLS"))
        root.addView(button("↻  Rebuild channel playlists"){refresh({showHome()},true)})
        root.addView(button("＋  Manage playlists"){managePlaylists()})
        root.addView(button("⚙  Player & TV settings"){settings()})
        root.addView(button("▶  Return to channel"){resumeLast()})
    }
    private val importPlaylistRequest=7291
    private fun importLocalPlaylist(){
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE)
            type="*/*"
        }
        if(intent.resolveActivity(packageManager)==null){
            Toast.makeText(this,"Android file picker unavailable on this TV",Toast.LENGTH_LONG).show()
            return
        }
        try{startActivityForResult(intent,importPlaylistRequest)}
        catch(e:Exception){showImportFallback()}
    }
    private fun showImportFallback(){
        AlertDialog.Builder(this).setTitle("Import M3U playlist")
            .setItems(arrayOf("Browse TV / USB storage","Open Android file picker","Paste M3U playlist text","Enter playlist URL")){_,choice->
                when(choice){
                    0->browsePlaylistFiles(null)
                    1->importLocalPlaylist()
                    2->pasteM3uPlaylist()
                    3->editPlaylist(null)
                }
            }.setNegativeButton("Cancel",null).show()
    }
    private fun pasteM3uPlaylist(){
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(16,8,16,8)}
        val name=EditText(this).apply{hint="Playlist name";setSingleLine()}
        val content=EditText(this).apply{
            hint="#EXTM3U followed by channel entries"
            minLines=5
            maxLines=10
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        layout.addView(name);layout.addView(content)
        AlertDialog.Builder(this).setTitle("Paste M3U").setView(layout)
            .setPositiveButton("Import"){_,_->
                val body=content.text.toString()
                val title=name.text.toString().trim().ifBlank{"Imported playlist"}
                saveImportedM3u(body,title)
            }.setNegativeButton("Cancel",null).show()
    }
    private fun saveImportedM3u(body:String,title:String){
        if(parseM3u(body,title).isEmpty()){
            Toast.makeText(this,"No valid M3U channels found",Toast.LENGTH_LONG).show()
            return
        }
        try{
            val file=java.io.File(filesDir,"import_"+System.currentTimeMillis()+".m3u")
            file.writeText(body)
            val list=playlists()
            list.add(Playlist(title,file.toURI().toString()))
            savePlaylists(list)
            refresh({managePlaylists()},true)
        }catch(e:Exception){
            Toast.makeText(this,"Import failed: "+e.message,Toast.LENGTH_LONG).show()
        }
    }
    private fun browsePlaylistFiles(directory:java.io.File?){
        val roots=if(directory==null){
            val dirs=mutableListOf<java.io.File>()
            // Detect removable volumes reported by Android, rather than relying only on /storage.
            val storage=getSystemService(android.content.Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            if(android.os.Build.VERSION.SDK_INT>=30){
                storage.storageVolumes.filter{it.state==android.os.Environment.MEDIA_MOUNTED}.forEach{volume->
                    volume.directory?.let{dirs.add(it)}
                }
            }
            dirs.add(java.io.File("/storage"))
            dirs.add(java.io.File("/sdcard"))
            dirs.add(java.io.File("/mnt/media_rw"))
            getExternalFilesDirs(null).filterNotNull().forEach{path->
                // Android may expose USB only through the app-specific folder.
                dirs.add(path)
                var parent=path.parentFile
                repeat(4){
                    if(parent!=null){
                        dirs.add(parent!!)
                        parent=parent?.parentFile
                    }
                }
            }
            dirs.distinctBy{it.canonicalPath}.filter{it.exists() && it.isDirectory}
        }else emptyList()
        val children=if(directory!=null)directory.listFiles()?.filter{
            it.isDirectory || (it.isFile && (it.name.endsWith(".m3u",true) || it.name.endsWith(".m3u8",true)))
        }?.sortedWith(compareBy<java.io.File>{!it.isDirectory}.thenBy{it.name.lowercase()}) ?: emptyList()
        else roots
        val entries=mutableListOf<Pair<String,java.io.File?>>()
        if(directory!=null)entries.add("← Back" to directory.parentFile?.takeIf{it.absolutePath!="/"})
        children.forEach{entries.add((if(it.isDirectory)"📁 " else "▶ ")+it.name to it)}
        if(entries.isEmpty()){
            AlertDialog.Builder(this).setTitle("No accessible USB files")
                .setMessage("The USB drive may be mounted but Android 14 is blocking direct folder access. Try the Android file picker, if installed, or import using an M3U URL.")
                .setPositiveButton("System file picker"){_,_->importLocalPlaylist()}
                .setNeutralButton("Playlist URL"){_,_->editPlaylist(null)}
                .setNegativeButton("Close",null).show()
            return
        }
        AlertDialog.Builder(this).setTitle(directory?.absolutePath ?: "TV / USB storage")
            .setItems(entries.map{it.first}.toTypedArray()){_,which->
                val selected=entries[which].second
                if(selected==null)browsePlaylistFiles(null)
                else if(selected.isDirectory)browsePlaylistFiles(selected)
                else thread{
                    try{
                        val body=selected.readText()
                        runOnUiThread{saveImportedM3u(body,selected.name)}
                    }catch(e:Exception){runOnUiThread{
                        Toast.makeText(this,"Cannot read file: "+e.message,Toast.LENGTH_LONG).show()
                    }}
                }
            }.setNeutralButton("System file picker"){_,_->importLocalPlaylist()}
            .setNegativeButton("Close",null).show()
    }
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=importPlaylistRequest || resultCode!=RESULT_OK)return
        val uri=data?.data ?:return
        thread{
            try{
                val body=contentResolver.openInputStream(uri)?.bufferedReader()?.use{it.readText()}
                    ?:throw IllegalArgumentException("Cannot read selected file")
                if(parseM3u(body,"Imported playlist").isEmpty())throw IllegalArgumentException("No valid M3U channels found")
                val file=java.io.File(filesDir,"import_"+System.currentTimeMillis()+".m3u")
                file.writeText(body)
                val displayName=uri.lastPathSegment?.substringAfterLast('/')?.take(40) ?: "Imported M3U"
                val list=playlists()
                list.add(Playlist(displayName,file.toURI().toString()))
                savePlaylists(list)
                runOnUiThread{refresh({managePlaylists()},true)}
            }catch(e:Exception){
                runOnUiThread{Toast.makeText(this,"Import failed: "+e.message,Toast.LENGTH_LONG).show()}
            }
        }
    }
    private fun addXtream(){
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(18,12,18,8)}
        val name=EditText(this).apply{hint="Playlist name";setSingleLine()}
        val server=EditText(this).apply{hint="Server URL (https://host:port)";setSingleLine()}
        val user=EditText(this).apply{hint="Username";setSingleLine()}
        val password=EditText(this).apply{
            hint="Password";setSingleLine()
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        listOf(name,server,user,password).forEach{layout.addView(it)}
        AlertDialog.Builder(this).setTitle("Xtream Codes — Live TV").setView(layout)
            .setPositiveButton("Add"){_,_->
                val n=name.text.toString().trim()
                val host=server.text.toString().trim().trimEnd('/')
                val u=user.text.toString().trim()
                val pass=password.text.toString()
                if(n.isBlank() || !(host.startsWith("https://") || host.startsWith("http://")) || u.isBlank() || pass.isBlank()){
                    Toast.makeText(this,"Enter server, username and password",Toast.LENGTH_LONG).show()
                }else{
                    val url=host+"/get.php?username="+Uri.encode(u)+"&password="+Uri.encode(pass)+"&type=m3u_plus&output=ts"
                    val list=playlists()
                    list.add(Playlist(n,url))
                    savePlaylists(list)
                    refresh({managePlaylists()},true)
                }
            }.setNegativeButton("Cancel",null).show()
    }
    private fun managePlaylists(){
        reset("Playlists")
        val list=playlists()
        list.forEachIndexed { index,p ->
            root.addView(button((if(p.enabled)"✓ " else "○ ")+p.name+" — "+(if(p.url.contains("/get.php?"))"Xtream Codes" else if(p.url.startsWith("file:"))"Local M3U" else p.url.take(40))){
                val opts=arrayOf("Enable / Disable","Edit","Delete")
                AlertDialog.Builder(this).setTitle(p.name).setItems(opts){_,which->
                    when(which){
                        0->{list[index]=p.copy(enabled=!p.enabled);savePlaylists(list);managePlaylists()}
                        1->editPlaylist(index)
                        2->AlertDialog.Builder(this).setMessage("Delete playlist?").setPositiveButton("Delete"){_,_->list.removeAt(index);savePlaylists(list);managePlaylists()}.setNegativeButton("Cancel",null).show()
                    }
                }.show()
            })
        }
        root.addView(button("↻ Refresh from original source URLs"){refresh({managePlaylists()},true)})
        root.addView(button("Add M3U URL"){editPlaylist(null)})
        root.addView(button("Import M3U file (TV / USB)"){showImportFallback()})
        root.addView(button("Add Xtream Codes"){addXtream()})
        root.addView(button("Back"){showHome()})
    }
    private fun editPlaylist(index:Int?){
        val old=index?.let{playlists()[it]}
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        val name=EditText(this).apply{hint="Playlist name";setSingleLine();setText(old?.name?:"")}
        val url=EditText(this).apply{hint="https://.../playlist.m3u";setSingleLine();setText(old?.url?:"")}
        layout.addView(name);layout.addView(url)
        AlertDialog.Builder(this).setTitle("Playlist").setView(layout).setPositiveButton("Save"){_,_->
            val n=name.text.toString().trim();val u=url.text.toString().trim()
            if(n.isNotEmpty() && (u.startsWith("https://") || u.startsWith("http://"))){
                val list=playlists();val p=Playlist(n,u,old?.enabled?:true)
                if(index==null)list.add(p) else list[index]=p
                savePlaylists(list)
                Toast.makeText(this,"Downloading and saving playlist…",Toast.LENGTH_SHORT).show()
                refresh({managePlaylists()})
            } else Toast.makeText(this,"Enter name and HTTP(S) URL",Toast.LENGTH_LONG).show()
        }.setNegativeButton("Cancel",null).show()
    }
    private fun cacheFile(p:Playlist):java.io.File{
        val digest=MessageDigest.getInstance("SHA-256").digest(p.url.toByteArray())
            .joinToString(""){"%02x".format(it)}
        return java.io.File(filesDir,"playlist_"+digest+".m3u")
    }
    // Limit a malformed/oversized feed before it can exhaust the TV's heap.
    // 32 MiB accommodates large IPTV lists without accepting unbounded input.
    private fun readPlaylistText(stream:java.io.InputStream):String {
        val limit=32*1024*1024
        val text=StringBuilder()
        stream.bufferedReader().use { reader->
            val buffer=CharArray(8192)
            while(true){
                val read=reader.read(buffer)
                if(read<0)break
                if(text.length+read>limit)throw java.io.IOException("Playlist exceeds 32 MiB safety limit")
                text.append(buffer,0,read)
            }
        }
        return text.toString()
    }
    private fun readPlaylistCache(file:java.io.File):String {
        if(file.length()>32L*1024*1024)throw java.io.IOException("Cached playlist exceeds safety limit")
        return file.inputStream().use{readPlaylistText(it)}
    }
    @Volatile private var refreshGeneration=0
    private fun cachedChannels():MutableList<Channel>{
        if(!licenseReady)return mutableListOf()
        val found=mutableListOf<Channel>()
        playlists().filter{it.enabled}.forEach{p->
            try{
                val file=cacheFile(p)
                if(file.exists())found.addAll(parseM3u(readPlaylistCache(file),p.name))
            }catch(e:Exception){android.util.Log.w("HomeTV","Playlist cache read failed",e)}
        }
        return found.distinctBy{it.source.lowercase()+"|"+it.name.lowercase()+"|"+it.url}.toMutableList()
    }
    private fun refresh(done:()->Unit,forceRebuild:Boolean=false){
        if(!licenseReady){showLicenseScreen("Please activate your license");return}
        val sources=playlists().filter{it.enabled}
        if(sources.isEmpty()){
            Toast.makeText(this,"No enabled playlists. Existing channels preserved.",Toast.LENGTH_LONG).show()
            done()
            return
        }
        if(forceRebuild)Toast.makeText(this,"Rebuilding playlists from source…",Toast.LENGTH_SHORT).show()
        val playingBeforeRefresh=channels.getOrNull(active)
        val playingUrlBeforeRefresh=playingBeforeRefresh?.url
        // Take an immutable reference snapshot; grouping thousands of channels
        // on the TV UI thread causes visible freezes during refresh.
        val previousChannels=channels.toList()
        val generation=++refreshGeneration
        thread(name="HomeTV-playlist-refresh"){
            val previousBySource=previousChannels.groupBy{it.source}
            val results=Array(sources.size){emptyList<Channel>()}
            val refreshed=BooleanArray(sources.size)
            // A playlist may contain thousands of channels. Do not create a
            // new thread for every configured source or parse caches on the UI.
            val pool=java.util.concurrent.Executors.newFixedThreadPool(minOf(3,sources.size))
            try{
                val tasks=sources.mapIndexed{index,p->
                    pool.submit {
                        try{
                            val body=if(p.url==licensedPlaylist)licensedM3u() else {
                                val connection=URL(p.url).openConnection().apply{
                                    connectTimeout=12000
                                    readTimeout=15000
                                    useCaches=false
                                    setRequestProperty("Cache-Control","no-cache, no-store, max-age=0")
                                    setRequestProperty("Pragma","no-cache")
                                    setRequestProperty("User-Agent","HomeTV/1.0 AndroidTV")
                                }
                                connection.getInputStream().use{readPlaylistText(it)}
                            }
                            val parsed=parseM3u(body,p.name)
                            if(parsed.isEmpty())throw IllegalStateException("No valid channels in "+p.name)
                            val discovered=parsed.filter{it.mediaType=="web" && it.url.contains("/channel/")}
                                .flatMap{discoverWebDirectory(it)}
                            results[index]=parsed+discovered
                            refreshed[index]=true
                            val file=cacheFile(p)
                            val temp=java.io.File(file.absolutePath+".tmp")
                            temp.writeText(body)
                            if(!temp.renameTo(file)){
                                temp.copyTo(file,overwrite=true)
                                temp.delete()
                            }
                        }catch(e:Exception){
                            android.util.Log.w("HomeTV","Playlist rebuild failed: "+p.name,e)
                            if(!forceRebuild){
                                try{
                                    val file=cacheFile(p)
                                    if(file.exists())results[index]=parseM3u(readPlaylistCache(file),p.name)
                                }catch(e:Exception){android.util.Log.w("HomeTV","Cache fallback failed",e)}
                            }
                        }
                    }
                }
                tasks.forEach{it.get()}
            }finally{pool.shutdownNow()}
            val updated=refreshed.count{it}
            val combined=mutableListOf<Channel>()
            sources.forEachIndexed{index,p->
                val loaded=results[index]
                if(loaded.isNotEmpty())combined.addAll(loaded)
                else {
                    if(forceRebuild){
                        val cached=try{
                            val file=cacheFile(p)
                            if(file.exists())parseM3u(readPlaylistCache(file),p.name) else emptyList()
                        }catch(_:Exception){emptyList()}
                        if(cached.isNotEmpty()){combined.addAll(cached);return@forEachIndexed}
                    }
                    combined.addAll(previousBySource[p.name].orEmpty())
                }
            }
            val nextChannels=combined.distinctBy{
                it.mediaType+"|"+it.source.lowercase(java.util.Locale.ROOT)+"|"+
                    it.name.lowercase(java.util.Locale.ROOT)+"|"+it.url
            }.toMutableList()
            runOnUiThread{
                if(generation!=refreshGeneration || !licenseReady || isFinishing || isDestroyed)return@runOnUiThread
                if(updated==0 && nextChannels.isEmpty()){
                    Toast.makeText(this,"Refresh failed. Existing channels preserved.",Toast.LENGTH_LONG).show()
                }else{
                    channels=nextChannels
                    if(forceRebuild)Toast.makeText(this,"Rebuilt "+updated+"/"+sources.size+" playlists • "+actualChannelCount()+" channels",Toast.LENGTH_LONG).show()
                }
                // A background refresh can reorder or remove entries while a
                // channel is playing. Never leave active pointing to a different
                // channel after replacing the channel list.
                if(active>=0){
                    val stillPlaying=playingUrlBeforeRefresh
                    val relocated=channels.indexOfFirst{it.url==stillPlaying &&
                        (playingBeforeRefresh==null || it.source==playingBeforeRefresh.source)}
                    if(relocated>=0)active=relocated
                    else{
                        stopPlayback()
                        showHome()
                    }
                }
                startBackgroundChannelChecks()
                done()
            }
        }
    }
    // Bounded background stream checks: two independent Media3 players, never the on-screen player.
    @Volatile private var backgroundProbeGeneration=0
    private val probeReachable=java.util.concurrent.ConcurrentHashMap<String,Boolean>()
    private var periodicProbeTask:Runnable?=null
    private var probeQueue=java.util.ArrayDeque<Channel>()
    private var backgroundProbeOffset=0
    private var probePositionByUrl:Map<String,Int> = emptyMap()
    private val probePlayers=mutableSetOf<ExoPlayer>()
    private var probeInFlight=0
    private val vlcProbeReachable=java.util.concurrent.ConcurrentHashMap<String,Boolean>()
    private val vlcProbeQueue=java.util.ArrayDeque<Channel>()
    private var vlcProbeCore:LibVLC?=null
    private var vlcProbePlayer:VlcPlayer?=null
    private var vlcProbeWatchdog:Runnable?=null
    private fun stopVlcProbe(){
        vlcProbeWatchdog?.let{handler.removeCallbacks(it)}
        vlcProbeWatchdog=null
        vlcProbePlayer?.let{try{it.stop();it.release()}catch(_:Exception){}}
        vlcProbePlayer=null
        vlcProbeCore?.let{try{it.release()}catch(_:Exception){}}
        vlcProbeCore=null
    }
    private fun launchNextVlcProbe(generation:Int){
        if(generation!=backgroundProbeGeneration || !licenseReady ||
            !prefs.getBoolean("background_url_checks",false) || vlcProbePlayer!=null)return
        while(vlcProbeQueue.isNotEmpty()){
            val channel=vlcProbeQueue.removeFirst()
            val url=channel.url.lowercase(java.util.Locale.ROOT)
            if(!(url.startsWith("http://") || url.startsWith("https://")) ||
                "youtube.com" in url || "youtu.be" in url ||
                channel.url==channels.getOrNull(active)?.url)continue
            try{
                val core=LibVLC(this,arrayListOf("--no-audio","--no-video","--network-caching=1200","--http-reconnect"))
                val probe=VlcPlayer(core)
                vlcProbeCore=core
                vlcProbePlayer=probe
                var completed=false
                fun finish(working:Boolean){
                    if(completed)return
                    completed=true
                    if(working && generation==backgroundProbeGeneration &&
                        prefs.getBoolean("background_url_checks",false)){
                        vlcProbeReachable[channel.url]=true
                        // This is a compatibility hint, not a proven LibVLC-only
                        // stream: only actual playback may persist that preference.
                    }
                    handler.post{
                        if(vlcProbePlayer===probe){
                            stopVlcProbe()
                            handler.post{launchNextVlcProbe(generation)}
                        }
                    }
                }
                probe.setEventListener{event->
                    when(event.type){
                        VlcPlayer.Event.Playing->finish(true)
                        VlcPlayer.Event.EncounteredError,VlcPlayer.Event.EndReached->finish(false)
                    }
                }
                val media=VlcMedia(core,Uri.parse(channel.url))
                media.addOption(":no-audio")
                media.addOption(":no-video")
                channel.headers["User-Agent"]?.let{media.addOption(":http-user-agent="+it)}
                channel.headers["Referer"]?.let{media.addOption(":http-referrer="+it)}
                probe.media=media
                media.release()
                vlcProbeWatchdog=Runnable{finish(false)}
                handler.postDelayed(vlcProbeWatchdog!!,6500L)
                probe.play()
                return
            }catch(_:Exception){stopVlcProbe()}
        }
    }

    private fun startBackgroundChannelChecks(){
        if(!licenseReady)return
        backgroundProbeGeneration++
        val generation=backgroundProbeGeneration
        periodicProbeTask?.let{handler.removeCallbacks(it)}
        // Background probes are nonessential. Avoid tearing down decoders
        // while a foreground channel is playing; old HTTP checks are ignored
        // by the generation guard.
        probeQueue.clear()
        probeInFlight=0
        vlcProbeQueue.clear()
        // An OFF toggle must not build and sort a huge probe queue.
        if(!prefs.getBoolean("background_url_checks",false))return
        // Probe closest channels first in the last remote-control direction.
        // Never interrupt the on-screen channel or probe it a second time.
        // Only the bounded probe window needs URL positions. Scanning every
        // channel on the UI thread made each background refresh O(playlist size).
        val firstPositionByUrl=HashMap<String,Int>(256)
        // Scan a rotating window, not always the first 250 streams. Even a
        // very large playlist is eventually covered without a giant queue.
        val snapshot=ArrayList<Channel>(250)
        val seenUrls=HashSet<String>()
        val total=channels.size
        val start=if(total>0)backgroundProbeOffset%total else 0
        var scanned=0
        while(scanned<total && snapshot.size<250){
            val position=(start+scanned)%total
            val candidate=channels[position]
            scanned++
            if(candidate.url!=channels.getOrNull(active)?.url && seenUrls.add(candidate.url)){
                firstPositionByUrl[candidate.url]=position
                snapshot.add(candidate)
            }
        }
        if(total>0)backgroundProbeOffset=(start+scanned)%total
        probePositionByUrl=firstPositionByUrl
        val anchor=active.coerceAtLeast(0)
        val count=channels.size.coerceAtLeast(1)
        val ordered=snapshot.sortedWith(compareBy<Channel>{candidate->
            val position=firstPositionByUrl[candidate.url] ?:0
            if(surfingDirection<0)(anchor-position+count)%count
            else (position-anchor+count)%count
        }.thenBy{it.name})
        ordered.forEach{probeQueue.addLast(it)}
        // LibVLC is expensive; enqueue only URLs for which Media3 already
        // failed in this scan. No speculative VLC probe for every channel.
        if(prefs.getBoolean("background_url_checks",false))launchNextProbe(generation)
    }
    // Lightweight network-only probes. Never instantiate decoders or record player compatibility.
    private fun launchNextProbe(generation:Int){
        if(generation!=backgroundProbeGeneration || !licenseReady || !prefs.getBoolean("background_url_checks",false))return
        if(!hasNetworkConnection()){
            periodicProbeTask?.let{handler.removeCallbacks(it)}
            periodicProbeTask=Runnable{startBackgroundChannelChecks()}
            handler.postDelayed(periodicProbeTask!!,15000L)
            return
        }
        val busy=active>=0 && (player?.playbackState==Player.STATE_BUFFERING ||
            player?.playbackState==Player.STATE_IDLE)
        val limit=if(busy)1 else 2
        // Rank at most once per dispatch, not once for every URL. The
        // playback direction is sampled on each dispatch for responsive CH+/CH-.
        if(probeInFlight<limit && probeQueue.size>1){
            val anchor=active.coerceAtLeast(0)
            val count=channels.size.coerceAtLeast(1)
            val firstPositionByUrl=probePositionByUrl
            val pending=probeQueue.toList()
            probeQueue.clear()
            pending.sortedBy{candidate->
                val position=firstPositionByUrl[candidate.url] ?:0
                if(surfingDirection<0)(anchor-position+count)%count
                else (position-anchor+count)%count
            }.forEach{probeQueue.addLast(it)}
        }
        while(probeInFlight<limit && probeQueue.isNotEmpty()){
            val channel=probeQueue.removeFirst()
            val uri=channel.url.lowercase(java.util.Locale.ROOT)
            if(!(uri.startsWith("http://") || uri.startsWith("https://")) ||
                "youtube.com" in uri || "youtu.be" in uri)continue
            probeInFlight++
            thread(name="HomeTV-stream-check"){
                val reachable:Boolean?=try{
                    val connection=URL(channel.url).openConnection() as java.net.HttpURLConnection
                    try{
                        connection.connectTimeout=3500
                        connection.readTimeout=3500
                        connection.instanceFollowRedirects=true
                        connection.setRequestProperty("Range","bytes=0-1023")
                        connection.setRequestProperty("User-Agent",channel.headers["User-Agent"] ?: "HomeTV/1.0 AndroidTV")
                        channel.headers.forEach{(key,value)->
                            if(!key.equals("User-Agent",true))connection.setRequestProperty(key,value)
                        }
                        val code=connection.responseCode
                        val contentType=connection.contentType?.lowercase(java.util.Locale.ROOT) ?: ""
                        when{
                            code in 200..299 && !contentType.contains("text/html")->true
                            code==404 || code==410->false
                            else->null
                        }
                    }finally{connection.disconnect()}
                }catch(_:Exception){null}
                handler.post{
                    if(generation!=backgroundProbeGeneration ||
                        !prefs.getBoolean("background_url_checks",false))return@post
                    probeInFlight=(probeInFlight-1).coerceAtLeast(0)
                    if(reachable!=null)probeReachable[channel.url]=reachable
                    // A successful HTTP check can restore surfing eligibility,
                    // but only actual playback can establish engine compatibility.
                    if(reachable==true)unavailableChannels.remove(channelKey(channel))
                    launchNextProbe(generation)
                }
            }
        }
        if(probeInFlight==0 && probeQueue.isEmpty() && generation==backgroundProbeGeneration){
            periodicProbeTask=Runnable{startBackgroundChannelChecks()}
            handler.postDelayed(periodicProbeTask!!,15*60*1000L)
        }
    }
    // Discover links from a bounded Web TV directory, without touching normal IPTV entries.
    // This extracts navigable channel pages; playable streams are resolved by the WebView.
    private fun discoverWebDirectory(entry:Channel):List<Channel>{
        val base=entry.url.trimEnd('/')
        if(entry.mediaType!="web" || !base.contains("/channel/"))return emptyList()
        val found=linkedMapOf<String,Channel>()
        val pages=6
        for(page in 1..pages){
            val address=if(page==1)base+"/" else base+"/page/"+page+"/"
            val html=try{
                val conn=URL(address).openConnection().apply{
                    connectTimeout=5000;readTimeout=7000
                    setRequestProperty("User-Agent","Mozilla/5.0 (Android TV) MyHomeTV")
                }
                conn.getInputStream().bufferedReader().use{it.readText().take(1_500_000)}
            }catch(_:Exception){break}
            val links=Regex("""<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""",RegexOption.IGNORE_CASE)
            var added=0
            links.findAll(html).take(250).forEach{match->
                val raw=match.groupValues[1].replace("&amp;","&")
                val url=try{URL(URL(address),raw).toString().substringBefore('#')}catch(_:Exception){""}
                if(!url.startsWith("https://") && !url.startsWith("http://"))return@forEach
                val host=try{URL(url).host.lowercase(java.util.Locale.ROOT)}catch(_:Exception){return@forEach}
                if(host !in setOf("bhoomtv.org","www.bhoomtv.org") || !URL(url).path.startsWith("/live/"))return@forEach
                val inner=match.groupValues[2]
                val alt=Regex("""alt=["']([^"']+)["']""",RegexOption.IGNORE_CASE).find(inner)?.groupValues?.get(1)
                val title=(alt?:inner.replace(Regex("<[^>]*>")," ").trim())
                    .replace("&amp;","&").replace(Regex("""\s+""")," ").trim()
                    .ifBlank{url.trimEnd('/').substringAfterLast('/').replace('-',' ')}
                val channelName=if(title.length<3 || title.equals("watch",true) ||
                    title.equals("live",true) || title.equals("play",true))
                    url.trimEnd('/').substringAfterLast('/').replace('-',' ')
                    else title
                val rawLogo=Regex("""(?:data-src|src)=["']([^"']+)["']""",RegexOption.IGNORE_CASE)
                    .find(inner)?.groupValues?.get(1)?:""
                val logo=try{if(rawLogo.isBlank())"" else URL(URL(address),rawLogo).toString()}catch(_:Exception){""}
                if(url !in found && found.size<400){
                    found[url]=Channel(channelName,url,entry.group,logo,entry.source,webChannel="auto",mediaType="web")
                    added++
                }
            }
            if(page>1 && added==0)break
        }
        return found.values.toList()
    }
    private fun parseM3u(body:String,source:String):List<Channel>{
        val out=mutableListOf<Channel>()
        var name="";var group="Other";var logo="";var epgId="";var webChannel=""
        var mediaType="live";var year="";var genre="";var artist="";var album="";var season="";var episode="";var description=""
        var headers=mutableMapOf<String,String>()
        // Compile attribute patterns once per playlist, not once per channel field.
        val attributes=listOf("group-title","tvg-logo","tvg-id","web-channel","user-agent","referrer","media-type","year","genre","artist","album","season","episode","series-name","description")
            .associateWith{key->Regex(Regex.escape(key)+"""="([^"]*)" """.trim())}
        fun attr(line:String,key:String)=attributes[key]?.find(line)?.groupValues?.get(1)?:""
        fun applyHeader(key:String,value:String){
            val normalized=when(key.lowercase()){
                "http-user-agent","user-agent"->"User-Agent"
                "http-referrer","http-referer","referer","referrer"->"Referer"
                "http-origin","origin"->"Origin"
                else->return
            }
            if(value.isNotBlank())headers[normalized]=value.trim()
        }
        body.lineSequence().forEach{raw->
            val line=raw.trim()
            when{
                line.startsWith("#EXTINF",true)->{
                    name=line.substringAfterLast(",","").trim()
                    group=attr(line,"group-title").ifBlank{"Other"}
                    logo=attr(line,"tvg-logo")
                    epgId=attr(line,"tvg-id")
                    webChannel=attr(line,"web-channel")
                    mediaType=attr(line,"media-type").trim().lowercase(java.util.Locale.ROOT).ifBlank{if(webChannel.isNotBlank() && !webChannel.equals("no",true))"web" else "live"}
                    if(mediaType !in setOf("live","movie","series","music","web"))mediaType="live"
                    year=attr(line,"year");genre=attr(line,"genre");artist=attr(line,"artist");album=attr(line,"album")
                    season=attr(line,"season");episode=attr(line,"episode");description=attr(line,"description")
                    if(mediaType=="series" && attr(line,"series-name").isNotBlank())group=attr(line,"series-name")
                    headers=mutableMapOf()
                    applyHeader("user-agent",attr(line,"user-agent"))
                    applyHeader("referer",attr(line,"referrer"))
                }
                line.startsWith("#EXTVLCOPT:",true)->{
                    val pair=line.substringAfter(":").split("=",limit=2)
                    if(pair.size==2)applyHeader(pair[0],pair[1])
                }
                line.startsWith("#EXTHTTP:",true)->{
                    val rawHeaders=line.substringAfter(":").trim()
                    try{
                        val obj=JSONObject(rawHeaders)
                        obj.keys().forEach{key->applyHeader(key,obj.optString(key))}
                    }catch(_:Exception){}
                }
                line.isNotEmpty() && !line.startsWith("#") && name.isNotEmpty()->{
                    val parts=line.split("|",limit=2)
                    val url=parts[0].trim()
                    val localHeaders=headers.toMutableMap()
                    if(parts.size==2)parts[1].split("&").forEach{kv->
                        val pair=kv.split("=",limit=2)
                        if(pair.size==2)applyHeaderTo(localHeaders,pair[0],pair[1])
                    }
                    // Some IPTV files contain comments or junk between stream
                    // URLs. Only recognized live-stream protocols are channels.
                    // Keep the EXTINF metadata active for multiple URLs under
                    // the same logical channel.
                    val scheme=url.substringBefore("://","").lowercase(java.util.Locale.ROOT)
                    if("://" in url && scheme in setOf(
                            "http","https","rtmp","rtmps","rtsp","rtp","udp","mmsh","mms")){
                        out.add(Channel(name,url,group,logo,source,localHeaders,epgId,webChannel,mediaType,year,genre,artist,album,season,episode,description))
                    }
                }
            }
        }
        addSavedWebTv(out)
        return out
    }
    private fun applyHeaderTo(map:MutableMap<String,String>,key:String,value:String){
        val name=when(key.lowercase()){
            "user-agent","http-user-agent"->"User-Agent"
            "referer","referrer","http-referrer"->"Referer"
            "origin"->"Origin"
            else->return
        }
        map[name]=java.net.URLDecoder.decode(value,"UTF-8")
    }
    private fun showChannels(){
        prefs.edit().putBoolean("favorites_surfing",false).apply()
        reset("Channels (${visibleIndices().size})")
        root.addView(button("Rebuild playlists"){refresh({showChannels()},true)})
        val groups=visibleIndices().map{channels[it].group}.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        // Virtualize groups too: do not create a Button for every playlist group.
        val list=ListView(this).apply{
            dividerHeight=1
            isFocusable=true
            isFocusableInTouchMode=true
            setBackgroundColor(0xFF101E31.toInt())
        }
        list.adapter=object:android.widget.BaseAdapter(){
            override fun getCount()=groups.size
            override fun getItem(position:Int):Any=groups[position]
            override fun getItemId(position:Int)=position.toLong()
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View{
                val row=(convertView as? TextView) ?:TextView(this@MainActivity).apply{
                    textSize=18f
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(22,17,18,17)
                    minHeight=62
                    isSingleLine=true
                    ellipsize=android.text.TextUtils.TruncateAt.END
                }
                row.text="▸ "+groups[position]
                return row
            }
        }
        list.setOnItemClickListener{_,_,position,_->showGroup(groups[position])}
        list.setOnItemLongClickListener{_,_,position,_->groupOptions(groups[position]);true}
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(button("Home"){showHome()})
    }
    private fun showGroup(group:String){
        prefs.edit().putBoolean("favorites_surfing",false).apply()
        reset(group)
        root.addView(button("Lock group"){toggleLock(group,true)})
        // Virtualized rows: never create thousands of Buttons on the UI thread.
        val indices=visibleIndices().filter{channels[it].group==group}
        val list=ListView(this).apply{
            dividerHeight=1
            isFocusable=true
            isFocusableInTouchMode=true
            setBackgroundColor(0xFF101E31.toInt())
        }
        list.adapter=object:android.widget.BaseAdapter(){
            override fun getCount()=indices.size
            override fun getItem(position:Int):Any=channels[indices[position]]
            override fun getItemId(position:Int)=indices[position].toLong()
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View{
                val row=(convertView as? TextView) ?:TextView(this@MainActivity).apply{
                    textSize=18f
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(22,17,18,17)
                    minHeight=62
                    isSingleLine=true
                    ellipsize=android.text.TextUtils.TruncateAt.END
                }
                row.text=channelLabel(indices[position])
                return row
            }
        }
        list.setOnItemClickListener{_,_,position,_->playIndex(preferredChannel(indices[position]))}
        list.setOnItemLongClickListener{_,_,position,_->
            showChannelListQuickActions(indices[position]){
                (list.adapter as android.widget.BaseAdapter).notifyDataSetChanged()
                list.setSelection(position)
                list.requestFocus()
            }
            true
        }
        root.addView(label("Long press: Hide / Unhide · Favourite · Lock / Unlock"))
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(button("Groups"){showChannels()})
    }
    private fun manageLockedChannels(){
        requirePin{
            val locked=visibleIndices().filter{isLocked(channels[it])}
            if(locked.isEmpty()){
                Toast.makeText(this,"No locked channels",Toast.LENGTH_SHORT).show()
            }else{
                AlertDialog.Builder(this).setTitle("Unlock channel or group")
                    .setItems(locked.map{channels[it].name+" ("+(it+1)+")"}.toTypedArray()){_,position->
                        val c=channels[locked[position]]
                        if(lockIds.contains(id(c))){
                            val set=lockIds.toMutableSet();set.remove(id(c))
                            prefs.edit().putStringSet("locked_channels",set).apply()
                        }
                        if(lockGroups.contains(c.group)){
                            val set=lockGroups.toMutableSet();set.remove(c.group)
                            prefs.edit().putStringSet("locked_groups",set).apply()
                        }
                        Toast.makeText(this,"Unlocked "+c.name,Toast.LENGTH_SHORT).show()
                        if(active<0)settings()
                    }.setNegativeButton("Cancel",null).show()
            }
        }
    }
    private fun id(c:Channel)=c.source+"|"+c.name+"|"+c.url
    private fun toggleLock(value:String,group:Boolean){
        requirePin {
            val key=if(group)"locked_groups" else "locked_channels"
            val set=(prefs.getStringSet(key,emptySet())?:emptySet()).toMutableSet()
            if(!set.add(value))set.remove(value)
            prefs.edit().putStringSet(key,set).apply()
            if(active>=0 && channels.getOrNull(active)?.let{isLocked(it)}==true)allChannels()
            else if(active>=0)showBanner("Channel lock updated")
            else allChannels()
        }
    }
    private fun hash(pin:String)=MessageDigest.getInstance("SHA-256").digest(pin.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun requirePin(action:()->Unit){
        val saved=prefs.getString("pin",null)
        if(saved==null){Toast.makeText(this,"Set a PIN in Settings first",Toast.LENGTH_LONG).show();return}
        val field=EditText(this).apply{inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;hint="Password"}
        showSideDialog(AlertDialog.Builder(this).setTitle("Enter password").setView(field).setPositiveButton("Unlock"){_,_->
            if(hash(field.text.toString())==saved)action() else Toast.makeText(this,"Incorrect password",Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Cancel",null).create())
    }
    private fun showBanner(message:String, error:Boolean=false){
        val screen=videoScreen ?: return
        bannerTask?.let{handler.removeCallbacks(it)}
        banner?.let{screen.removeView(it)}
        val text=TextView(this).apply {
            this.text=message
            textSize=20f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(informationColor(if(error)0x842020 else 0x15283C))
            setPadding(24,18,24,18)
        }
        banner=text
        screen.addView(text,FrameLayout.LayoutParams((resources.displayMetrics.widthPixels*0.52f).toInt(),-2,Gravity.TOP or Gravity.RIGHT))
        if(!error){
            val task=Runnable{if(banner===text){screen.removeView(text);banner=null}}
            bannerTask=task
            handler.postDelayed(task,2500)
        }
    }
    private fun showSideDialog(dialog:AlertDialog,side:Int=Gravity.RIGHT){
        dialog.show()
        dialog.window?.let{window->
            window.setGravity(side or Gravity.CENTER_VERTICAL)
            window.setLayout((resources.displayMetrics.widthPixels*0.44f).toInt().coerceAtLeast(320),WindowManager.LayoutParams.WRAP_CONTENT)
            window.setDimAmount(0.06f)
            window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(informationColor(0x202B3A)))
            dialog.listView?.apply{
                // A selected-row callback that paints item backgrounds leaves
                // previously selected rows blue when Android recycles the views.
                // Use the native focused/selected drawable instead.
                selector=android.graphics.drawable.StateListDrawable().apply{
                    addState(intArrayOf(android.R.attr.state_pressed),
                        android.graphics.drawable.ColorDrawable(0xFF267CC1.toInt()))
                    addState(intArrayOf(android.R.attr.state_focused),
                        android.graphics.drawable.ColorDrawable(0xFF267CC1.toInt()))
                    addState(intArrayOf(android.R.attr.state_selected),
                        android.graphics.drawable.ColorDrawable(0xFF267CC1.toInt()))
                    addState(intArrayOf(),
                        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                }
                choiceMode=android.widget.ListView.CHOICE_MODE_NONE
                clearChoices()
                isFocusable=true
                isFocusableInTouchMode=true
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }
    private fun confirmExit(){
        val dialog=AlertDialog.Builder(this).setTitle("Exit playback?")
            .setMessage("Stop the current video and return to Home?")
            .setPositiveButton("Yes"){_,_->stopPlayback();showHome()}
            .setNegativeButton("No",null).create()
        showSideDialog(dialog)
        // Android TV: initial OK confirms Yes; Back dismisses and resumes playback.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }
    private fun audioTracks(){
        val exo=player
        if(exo==null){Toast.makeText(this,"Audio track selection is available for IPTV streams",Toast.LENGTH_LONG).show();return}
        val entries=mutableListOf<Pair<androidx.media3.common.TrackGroup,Int>>()
        val names=mutableListOf<String>()
        for(group in exo.currentTracks.groups){
            if(group.type!=C.TRACK_TYPE_AUDIO)continue
            for(i in 0 until group.length){
                val fmt=group.getTrackFormat(i)
                val language=fmt.language ?: "Unknown language"
                val codec=fmt.sampleMimeType ?: "Unknown codec"
                names.add(language+" · "+codec+(if(group.isTrackSelected(i))" ✓" else "")+(if(!group.isTrackSupported(i))" (unsupported)" else ""))
                entries.add(Pair(group.mediaTrackGroup,i))
            }
        }
        if(entries.isEmpty()){AlertDialog.Builder(this).setTitle("Audio tracks").setMessage("No audio track was detected for this channel. Try another stream URL.").setPositiveButton("OK",null).show();return}
        AlertDialog.Builder(this).setTitle("Select audio track").setItems(names.toTypedArray()){_,position->
            val (group,index)=entries[position]
            exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group,listOf(index)))
                .build()
            showBanner("Audio track: "+names[position])
        }.setNeutralButton("Automatic"){_,_->
            exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        }.setNegativeButton("Cancel",null).show()
    }

    // Media3 subtitle choices are saved per logical channel. This does not alter
    // the selected playback engine or the stream URL.
    private fun subtitlePreference(c:Channel):String =
        prefs.getString("subtitle_"+hash(channelKey(c)),"auto") ?: "auto"

    private fun applySubtitlePreference(c:Channel){
        val exo=player ?:return
        val pref=subtitlePreference(c)
        val builder=exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,pref=="off")
        if(pref!="off" && pref!="auto"){
            builder.setPreferredTextLanguage(pref)
                .setSelectUndeterminedTextLanguage(false)
        }else{
            builder.setPreferredTextLanguage(null)
                .setSelectUndeterminedTextLanguage(false)
        }
        exo.trackSelectionParameters=builder.build()
    }

    private fun subtitleTracks(channelIndex:Int=active){
        val c=channels.getOrNull(channelIndex) ?:return
        if(active !in channels.indices || !sameChannel(c,channels[active])){
            showSideDialog(AlertDialog.Builder(this).setTitle(c.name+" — Subtitles")
                .setMessage("Subtitle tracks can be selected after this channel starts playing.")
                .setPositiveButton("Watch channel"){_,_->playIndex(preferredChannel(channelIndex))}
                .setNegativeButton("Cancel",null).create())
            return
        }
        val exo=player
        if(exo==null){
            AlertDialog.Builder(this).setTitle("Subtitles")
                .setMessage("Subtitle selection is available for streams playing through Media3. This channel is using another playback engine.")
                .setPositiveButton("OK",null).show()
            return
        }
        val choices=mutableListOf("Off","Automatic")
        val entries=mutableListOf<Pair<androidx.media3.common.TrackGroup,Int>>()
        val languages=mutableListOf<String>()
        for(group in exo.currentTracks.groups){
            if(group.type!=C.TRACK_TYPE_TEXT)continue
            for(i in 0 until group.length){
                if(!group.isTrackSupported(i))continue
                val format=group.getTrackFormat(i)
                val language=format.language ?: "und"
                val label=if(language=="und")"Undetermined" else
                    try{java.util.Locale.forLanguageTag(language).displayLanguage.ifBlank{language}}
                    catch(_:Exception){language}
                choices.add(label+" · "+(format.label ?: format.sampleMimeType ?: "Subtitles")+
                    (if(group.isTrackSelected(i))" ✓" else ""))
                entries.add(group.mediaTrackGroup to i)
                languages.add(language)
            }
        }
        AlertDialog.Builder(this).setTitle(c.name+" — Subtitles")
            .setItems(choices.toTypedArray()){_,choice->
                val editor=prefs.edit()
                if(choice==0){
                    editor.putString("subtitle_"+hash(channelKey(c)),"off").apply()
                    applySubtitlePreference(c)
                    showBanner("Subtitles off")
                }else if(choice==1){
                    editor.putString("subtitle_"+hash(channelKey(c)),"auto").apply()
                    applySubtitlePreference(c)
                    showBanner("Subtitles automatic")
                }else{
                    val position=choice-2
                    val (group,track)=entries[position]
                    val language=languages[position]
                    editor.putString("subtitle_"+hash(channelKey(c)),
                        if(language=="und")"auto" else language).apply()
                    applySubtitlePreference(c)
                    exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false)
                        .setOverrideForType(TrackSelectionOverride(group,listOf(track))).build()
                    showBanner("Subtitles: "+choices[choice])
                }
            }.setNegativeButton("Cancel",null).show()
    }

    private var sleepTimerTask:Runnable?=null
    private var sleepTimerDeadline:Long=0L

    private fun sleepTimer(){
        val remaining=if(sleepTimerDeadline>0L)
            ((sleepTimerDeadline-android.os.SystemClock.elapsedRealtime()+59999L)/60000L).coerceAtLeast(0L)
            else 0L
        val options=arrayOf(
            "Off"+(if(remaining>0L)" (cancel — "+remaining+" min left)" else ""),
            "15 minutes","30 minutes","45 minutes","60 minutes","90 minutes","120 minutes"
        )
        val minutes=intArrayOf(0,15,30,45,60,90,120)
        AlertDialog.Builder(this).setTitle("Sleep timer — stop TV playback")
            .setItems(options){_,which->
                sleepTimerTask?.let{handler.removeCallbacks(it)}
                sleepTimerTask=null
                val delay=minutes[which]
                if(delay==0){
                    sleepTimerDeadline=0L
                    if(active>=0)showBanner("Sleep timer off")
                    else Toast.makeText(this,"Sleep timer off",Toast.LENGTH_SHORT).show()
                }else{
                    sleepTimerDeadline=android.os.SystemClock.elapsedRealtime()+delay*60000L
                    val task=Runnable{
                        sleepTimerTask=null
                        sleepTimerDeadline=0L
                        stopPlayback()
                        showHome()
                        Toast.makeText(this,"Sleep timer finished — playback stopped",Toast.LENGTH_LONG).show()
                    }
                    sleepTimerTask=task
                    handler.postDelayed(task,delay*60000L)
                    if(active>=0)showBanner("Sleep timer: "+delay+" minutes")
                    else Toast.makeText(this,"Sleep timer: "+delay+" minutes",Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("Cancel",null).show()
    }
    private fun showChannelOptions(){
        if(active !in channels.indices)return
        val c=channels[active]
        val opts=arrayOf(
            if(channels.any{sameChannel(it,c) && favoriteIds.contains(id(it))})"Remove favorite" else "Add favorite",
            if(isManuallyHidden(c))"Unhide channel" else "Hide channel",
            if(isLocked(c))"Unlock channel" else "Lock channel",
            "Alternative Source",
            "Aspect Ratio",
            "Picture Quality",
            "Audio tracks",
            "Subtitles",
            "Sleep timer",
            "Playback Engine",
            "Channel list"
        )
        val dialog=AlertDialog.Builder(this).setTitle(numberFor(active).toString()+". "+c.name+" Settings")
            .setItems(opts){_,choice->
                when(choice){
                    0->favorite(c)
                    1->{toggleManualHide(c);showBanner(if(isManuallyHidden(c))"Channel hidden until manually restored" else "Channel restored")}
                    2->toggleLock(id(c),false)
                    3->selectAlternative(active)
                    4->selectScreenMode(active)
                    5->selectQuality(active)
                    6->audioTracks()
                    7->subtitleTracks()
                    8->sleepTimer()
                    9->chooseChannelEngine(active)
                    10->showOverlayList()
                }
            }.setNegativeButton("Close",null).create()
        showSideDialog(dialog)
    }
    private fun sideAction(side:String){
        when(prefs.getString("side_"+side,if(side=="left")"Channel list" else "Channel settings")){
            "Channel list"->showOverlayList()
            "Channel settings"->showChannelOptions()
            "Previous channel"->switchChannel(-1)
            "Next channel"->switchChannel(1)
            "Fit / Fill"->toggleScreenMode()
        }
    }
    private fun chooseSideAction(side:String){
        val actions=arrayOf("Channel list","Channel settings","Previous channel","Next channel","Fit / Fill","Do nothing")
        val current=prefs.getString("side_"+side,if(side=="left")"Channel list" else "Channel settings")
        AlertDialog.Builder(this).setTitle("Remote "+side+" button")
            .setSingleChoiceItems(actions,actions.indexOf(current)){dialog,index->
                prefs.edit().putString("side_"+side,actions[index]).apply()
                dialog.dismiss();settings()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun updateClock(){
        clockTask?.let{handler.removeCallbacks(it)}
        clockTask=null
        val screen=videoScreen ?:return
        screen.findViewWithTag<View>("clock_overlay")?.let{screen.removeView(it)}
        if(!prefs.getBoolean("clock_enabled",false) || (!prefs.getBoolean("clock_show_date",true) && !prefs.getBoolean("clock_show_time",true)))return
        val clock=TextView(this).apply{
            tag="clock_overlay"
            setTextColor(android.graphics.Color.WHITE)
            setShadowLayer(3f,1f,2f,android.graphics.Color.BLACK)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val size=prefs.getInt("clock_size",18).coerceIn(12,36)
            textSize=size.toFloat()
            setPadding(12,8,12,8)
            isFocusable=false
            isClickable=false
        }
        val position=prefs.getString("clock_position","Top right") ?: "Top right"
        val gravity=when(position){
            "Top left"->Gravity.TOP or Gravity.LEFT
            "Bottom left"->Gravity.BOTTOM or Gravity.LEFT
            "Bottom right"->Gravity.BOTTOM or Gravity.RIGHT
            "Top center"->Gravity.TOP or Gravity.CENTER_HORIZONTAL
            "Bottom center"->Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else->Gravity.TOP or Gravity.RIGHT
        }
        val params=FrameLayout.LayoutParams(-2,-2,gravity).apply{
            setMargins(16,16,16,16)
        }
        screen.addView(clock,params)
        fun render(){
            val pattern=if(prefs.getBoolean("clock_24h",true))"HH:mm" else "hh:mm a"
            val date=java.text.SimpleDateFormat("dd MMM yy",java.util.Locale.ENGLISH).format(java.util.Date())
            val time=java.text.SimpleDateFormat(pattern,java.util.Locale.ENGLISH).format(java.util.Date())
            clock.text=when{
                prefs.getBoolean("clock_show_date",true) && prefs.getBoolean("clock_show_time",true)->date+"  •  "+time
                prefs.getBoolean("clock_show_date",true)->date
                else->time
            }
        }
        render()
        val task=object:Runnable{
            override fun run(){
                if(videoScreen!==screen || clock.parent!==screen)return
                render()
                handler.postDelayed(this,15000L)
            }
        }
        clockTask=task
        handler.postDelayed(task,15000L)
    }
    private fun unmuteYoutube(viewer:WebView,notify:Boolean=true){
        // Only request sound; do not restart the video or click a mute button
        // that could accidentally toggle an already unmuted player back to mute.
        viewer.evaluateJavascript("""(function(){
            var v=document.querySelector('video');
            if(!v)return 'Video not ready';
            try{
                v.defaultMuted=false;
                v.muted=false;
                v.volume=1;
                var yt=document.getElementById('movie_player');
                if(yt && typeof yt.unMute==='function')yt.unMute();
            }catch(e){}
            return v.muted?'YouTube requires manual unmute':'Sound requested';
        })();"""){result->
            if(notify && result.contains("requires manual"))
                Toast.makeText(this@MainActivity,"Press volume up or use the YouTube mute button to enable sound",Toast.LENGTH_SHORT).show()
        }
    }
    private fun isActiveYoutubeMedia():Boolean{
        val c=channels.getOrNull(active)?:return false
        if(c.mediaType !in setOf("movie","series","music"))return false
        val host=try{Uri.parse(c.url).host?.lowercase(java.util.Locale.ROOT).orEmpty()}catch(_:Exception){""}
        return host=="youtu.be" || host=="youtube.com" || host.endsWith(".youtube.com")
    }
    private fun youtubeRemoteAction(action:String){
        val viewer=web?:return
        val command=when(action){
            "toggle"->"if(v.paused){v.play()}else{v.pause()}"
            "back"->"v.currentTime=Math.max(0,v.currentTime-10)"
            "forward"->"v.currentTime=Math.min(isFinite(v.duration)?v.duration:v.currentTime+10,v.currentTime+10)"
            else->return
        }
        viewer.evaluateJavascript("(function(){var v=document.querySelector('video');if(v){"+command+";return true;}var p=document.getElementById('movie_player');if(p){"+
            when(action){
                "toggle"->"if(p.getPlayerState()===1)p.pauseVideo();else p.playVideo();"
                "back"->"p.seekTo(Math.max(0,p.getCurrentTime()-10),true);"
                else->"p.seekTo(p.getCurrentTime()+10,true);"
            }+"return true;}return false;})()",null)
    }
    private var youtubeProgressTask:Runnable?=null
    private fun beginYoutubeProgressTracking(viewer:WebView,channelIndex:Int,screen:FrameLayout){
        youtubeProgressTask?.let{handler.removeCallbacks(it)}
        val generation=playbackGeneration
        val task=object:Runnable{
            override fun run(){
                if(web!==viewer || active!=channelIndex || videoScreen!==screen || playbackGeneration!=generation || youtubeProgressTask!==this)return
                viewer.evaluateJavascript("(function(){var v=document.querySelector('video');return v?Math.floor(v.currentTime||0):0;})()"){value->
                    val seconds=value.trim().trim('"').toLongOrNull()?:0L
                    if(seconds>0 && web===viewer && active==channelIndex && videoScreen===screen && playbackGeneration==generation)
                        channels.getOrNull(channelIndex)?.let{prefs.edit().putLong(mediaKey(it),seconds).apply()}
                }
                handler.postDelayed(this,5000L)
            }
        }
        youtubeProgressTask=task
        handler.postDelayed(task,5000L)
    }
    private fun requestYoutubeStartupAudio(viewer:WebView,channelIndex:Int,screen:FrameLayout){
        // YouTube inserts its video asynchronously. Retry only while audio is
        // unavailable; do not restart playback or affect Media3/LibVLC streams.
        val generation=playbackGeneration
        val delays=longArrayOf(600L,1400L,2700L,4500L,7000L)
        youtubeUnmuteTask?.let{handler.removeCallbacks(it)}
        var attempt=0
        val retry=object:Runnable{
            override fun run(){
                if(web!==viewer || active!=channelIndex || videoScreen!==screen ||
                    playbackGeneration!=generation)return
                viewer.evaluateJavascript("""(function(){
                    var v=document.querySelector('video');
                    if(!v || v.paused)return 'waiting';
                    try{
                        v.defaultMuted=false;v.muted=false;v.volume=1;
                        var yt=document.getElementById('movie_player');
                        if(yt && typeof yt.unMute==='function')yt.unMute();
                    }catch(e){}
                    return (!v.muted && v.volume>0)?'unmuted':'blocked';
                })();"""){result->
                    if(web!==viewer || active!=channelIndex || videoScreen!==screen ||
                        playbackGeneration!=generation)return@evaluateJavascript
                    if(result=="\"unmuted\""){
                        youtubeUnmuteTask=null
                        return@evaluateJavascript
                    }
                    attempt++
                    if(attempt<delays.size){
                        handler.postDelayed(this,delays[attempt]-delays[attempt-1])
                    }else youtubeUnmuteTask=null
                }
            }
        }
        youtubeUnmuteTask=retry
        handler.postDelayed(retry,delays[0])
    }
    private fun clockSettings(){
        val choices=arrayOf(
            "Date & time overlay: "+if(prefs.getBoolean("clock_enabled",false))"ON" else "OFF",
            "Show date: "+if(prefs.getBoolean("clock_show_date",true))"ON" else "OFF",
            "Show time: "+if(prefs.getBoolean("clock_show_time",true))"ON" else "OFF",
            "Time format: "+if(prefs.getBoolean("clock_24h",true))"24-hour" else "12-hour",
            "Text size: "+prefs.getInt("clock_size",18)+" sp",
            "Position: "+(prefs.getString("clock_position","Top right") ?: "Top right")
        )
        val clockDialog=AlertDialog.Builder(this).setTitle("Date & time overlay · dd MMM yy")
            .setItems(choices){_,which->
                when(which){
                    0->{prefs.edit().putBoolean("clock_enabled",!prefs.getBoolean("clock_enabled",false)).apply();updateClock();clockSettings()}
                    1->{prefs.edit().putBoolean("clock_show_date",!prefs.getBoolean("clock_show_date",true)).apply();updateClock();clockSettings()}
                    2->{prefs.edit().putBoolean("clock_show_time",!prefs.getBoolean("clock_show_time",true)).apply();updateClock();clockSettings()}
                    3->{prefs.edit().putBoolean("clock_24h",!prefs.getBoolean("clock_24h",true)).apply();updateClock();clockSettings()}
                    4->{
                        val sizes=intArrayOf(12,14,16,18,20,24,28,32,36)
                        AlertDialog.Builder(this).setTitle("Clock text size")
                            .setSingleChoiceItems(sizes.map{ it.toString()+" sp" }.toTypedArray(),sizes.indexOf(prefs.getInt("clock_size",18)).coerceAtLeast(0)){dialog,i->
                                prefs.edit().putInt("clock_size",sizes[i]).apply();dialog.dismiss();updateClock();clockSettings()
                            }.setNegativeButton("Cancel",null).show()
                    }
                    5->{
                        val positions=arrayOf("Top left","Top center","Top right","Bottom left","Bottom center","Bottom right")
                        AlertDialog.Builder(this).setTitle("Clock position")
                            .setSingleChoiceItems(positions,positions.indexOf(prefs.getString("clock_position","Top right")).coerceAtLeast(0)){dialog,i->
                                prefs.edit().putString("clock_position",positions[i]).apply();dialog.dismiss();updateClock();clockSettings()
                            }.setNegativeButton("Cancel",null).show()
                    }
                }
            }.setNegativeButton("Close",null).create()
        showSideDialog(clockDialog)
    }
    private var channelDigits=""
    private var channelDigitTask:Runnable?=null
    private var numericOverlay:TextView?=null
    private fun clearNumericOverlay(){
        numericOverlay?.let{view->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
        }
        numericOverlay=null
    }
    private fun showNumericOverlay(){
        val screen=videoScreen ?:return
        val view=numericOverlay?.takeIf{it.parent===screen} ?:TextView(this).apply{
            textSize=18f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(informationColor(0x15283C))
            setPadding(18,12,18,12)
            gravity=Gravity.CENTER_VERTICAL or Gravity.LEFT
            screen.addView(this,FrameLayout.LayoutParams((resources.displayMetrics.widthPixels*0.62f).toInt(),-2,Gravity.BOTTOM or Gravity.LEFT))
            numericOverlay=this
        }
        val typed=channelDigits.toIntOrNull()
        val channel=typed?.let{number->channelNumbers().getOrNull(number-1)}?.let{channels.getOrNull(it)}
        view.text=if(channel!=null) channelDigits+". "+channel.name else "Channel "+channelDigits
        view.bringToFront()
    }
    private fun enterChannelDigit(digit:Int){
        channelDigitTask?.let{handler.removeCallbacks(it)}
        channelDigits=(channelDigits+digit).takeLast(5)
        val typed=channelDigits.toIntOrNull()
        // Lightweight number display; do not rebuild EPG or video while typing.
        showNumericOverlay()
        if(typed==null || typed !in 1..channelNumbers().size)
            showBanner("Channel "+channelDigits+" is not available")
        val task=Runnable{tuneTypedChannel()}
        channelDigitTask=task
        handler.postDelayed(task,1800)
    }
    private fun tuneTypedChannel(){
        channelDigitTask?.let{handler.removeCallbacks(it)}
        channelDigitTask=null
        val number=channelDigits.toIntOrNull()
        channelDigits=""
        clearNumericOverlay()
        if(number==null)return
        if(number in 1..channelNumbers().size)playIndex(preferredChannel(channelNumbers()[number-1]))
        else showBanner("Channel "+number+" is not available",true)
    }
    private var previewIndex=-1
    private var previewTask:Runnable?=null
    private var previewVideoTask:Runnable?=null
    private var previewPlayer:ExoPlayer?=null
    private var previewPanel:FrameLayout?=null
    private var previewGeneration=0
    private fun closeVideoPreview(){
        previewGeneration++
        previewVideoTask?.let{handler.removeCallbacks(it)}
        previewVideoTask=null
        previewPlayer?.release()
        previewPlayer=null
        previewPanel?.let{panel->(panel.parent as? android.view.ViewGroup)?.removeView(panel)}
        previewPanel=null
    }
    private fun showVideoPreview(index:Int){
        closeVideoPreview()
        if(!prefs.getBoolean("live_channel_preview",false))return
        val screen=videoScreen ?:return
        if(index !in channels.indices)return
        val c=channels[preferredChannel(index)]
        if(c.url.contains("youtube.com") || c.url.contains("youtu.be"))return
        val width=(resources.displayMetrics.widthPixels*0.28f).toInt().coerceAtLeast(220)
        val height=(width*9/16).coerceAtLeast(120)
        val panel=FrameLayout(this).apply{
            setBackgroundColor(0xFF101E31.toInt())
            elevation=12f
        }
        previewPanel=panel
        val params=FrameLayout.LayoutParams(width,height,Gravity.TOP or Gravity.RIGHT).apply{
            topMargin=(resources.displayMetrics.density*52).toInt()
            rightMargin=(resources.displayMetrics.density*18).toInt()
        }
        screen.addView(panel,params)
        panel.addView(TextView(this).apply{
            text="LIVE PREVIEW  •  "+c.name
            textSize=13f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(8,8,8,8)
        },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        val generation=previewGeneration
        val task=Runnable{
            if(generation!=previewGeneration || previewIndex!=index || videoScreen!==screen)return@Runnable
            try{
                val factory=DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(4500).setReadTimeoutMs(6000)
                    .setAllowCrossProtocolRedirects(true)
                    .setDefaultRequestProperties(c.headers)
                val previewBuffer=DefaultLoadControl.Builder()
                    .setBufferDurationsMs(500,2500,250,500).build()
                val exo=ExoPlayer.Builder(this, DefaultRenderersFactory(this)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON))
                    .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                    .setLoadControl(previewBuffer)
                    .build()
                previewPlayer=exo
                exo.volume=0f
                exo.setAudioAttributes(AudioAttributes.DEFAULT,false)
                val view=PlayerView(this).apply{
                    player=exo
                    useController=false
                    resizeMode=androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                panel.addView(view,0,FrameLayout.LayoutParams(-1,-1))
                exo.setMediaItem(MediaItem.fromUri(c.url))
                exo.prepare()
                if(c.mediaType in setOf("movie","series","music")){
                    val resume=mediaResumeSeconds(c)
                    if(resume>0)exo.seekTo(resume*1000L)
                }
                exo.play()
                exo.addListener(object:Player.Listener{
                    override fun onPlayerError(error:PlaybackException){
                        if(previewPlayer===exo)closeVideoPreview()
                    }
                })
            }catch(e:Exception){
                android.util.Log.w("HomeTV","Preview unavailable",e)
                closeVideoPreview()
            }
        }
        previewVideoTask=task
        handler.postDelayed(task,900L)
    }
    private fun previewChannel(delta:Int){
        surfingDirection=if(delta>=0)1 else -1
        if(channels.isEmpty() || surfingIndices().isEmpty())return
        previewTask?.let{handler.removeCallbacks(it)}
        val available=surfingIndices()
        val current=if(previewIndex>=0)previewIndex else active
        val next=nextSurfingIndex(current,delta,available)
        previewIndex=next
        showChannelInfo(previewIndex)
        showVideoPreview(previewIndex)
        val task=Runnable{previewIndex=-1;closeVideoPreview()}
        previewTask=task
        handler.postDelayed(task,8000)
    }
    private fun screenInfoOnStart(index:Int){
        handler.postDelayed({if(active==index)showChannelInfo(index)},500)
        if(prefs.getBoolean("show_channel_epg",true) && epgGuide.isEmpty() && !prefs.getString("epg_xmltv_url","").isNullOrBlank())loadEpg{if(active==index)showChannelInfo(index)}
    }
    private fun informationAlpha():Int=prefs.getInt("information_opacity",65).coerceIn(20,100)*255/100
    private fun informationColor(rgb:Int):Int=(informationAlpha() shl 24) or (rgb and 0xFFFFFF)
    private fun chooseInformationTransparency(){
        val levels=arrayOf("20% • Very transparent","35% • Transparent","50% • Medium","65% • Recommended","80% • Darker","100% • Solid")
        val values=intArrayOf(20,35,50,65,80,100)
        val selected=values.indexOf(prefs.getInt("information_opacity",65)).coerceAtLeast(0)
        showSideDialog(AlertDialog.Builder(this).setTitle("Information panel opacity")
            .setSingleChoiceItems(levels,selected){dialog,which->
                prefs.edit().putInt("information_opacity",values[which]).apply()
                dialog.dismiss()
                if(active in channels.indices)showChannelInfo(active)
            }.setNegativeButton("Cancel",null).create())
    }
    private fun showChannelInfo(index:Int,extra:String=""){
        val screen=videoScreen ?:return
        val channel=channels.getOrNull(index) ?:return
        bannerTask?.let{handler.removeCallbacks(it)}
        banner?.let{screen.removeView(it)}
        val panel=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(18,12,18,12)
            setBackgroundColor(informationColor(0x15283C))
        }
        panel.addView(logoView(channel,64))
        panel.addView(TextView(this).apply{
            val now=System.currentTimeMillis()
            val epg=if(prefs.getBoolean("show_channel_epg",true))programmeList(channel).filter{it.end>now}.take(3).joinToString("\n"){programmeTime(it.start)+"  "+it.title} else ""
            val playing=if(index==active)when{
                player!=null->"Media3"
                vlcPlayer!=null->"LibVLC"
                else->"Starting"
            } else engineFor(channel)
            val current=if(prefs.getBoolean("show_channel_epg",true))
                programmeList(channel).firstOrNull{it.start<=now && it.end>now} else null
            val next=if(prefs.getBoolean("show_channel_epg",true))
                programmeList(channel).filter{it.start>now}.minByOrNull{it.start} else null
            text=buildString{
                append(numberFor(index)).append(". ").append(channel.name)
                append("\nPlayer: ").append(playing)
                if(current!=null)append("\nNow: ").append(current.title)
                    .append(" (").append(programmeTime(current.start)).append(" – ")
                    .append(programmeTime(current.end)).append(")")
                if(next!=null)append("\nNext: ").append(next.title)
                    .append(" (").append(programmeTime(next.start)).append(" – ")
                    .append(programmeTime(next.end)).append(")")
            }
            textSize=18f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(18,4,8,4)
        })
        banner=panel
        screen.addView(panel,FrameLayout.LayoutParams((resources.displayMetrics.widthPixels*0.62f).toInt(),-2,Gravity.BOTTOM or Gravity.LEFT))
        val task=Runnable{if(banner===panel){screen.removeView(panel);banner=null}}
        bannerTask=task
        handler.postDelayed(task,4000)
    }
    private fun confirmPreview(){
        val target=previewIndex
        previewIndex=-1
        closeVideoPreview()
        previewTask?.let{handler.removeCallbacks(it)}
        previewTask=null
        if(target in channels.indices)playIndex(preferredChannel(target)) else showBanner("Use ▲ / ▼ to change channels")
    }
    private val logoCache=object:android.util.LruCache<String,android.graphics.Bitmap>(6*1024*1024){
        override fun sizeOf(key:String,value:android.graphics.Bitmap)=value.byteCount
    }
    // Bound concurrent logo downloads so large playlists cannot create
    // hundreds of network threads at once.
    // Bound both active logo downloads and pending requests. Scrolling a
    // 10,000-channel list must never queue 10,000 bitmap downloads.
    private val logoExecutor=java.util.concurrent.ThreadPoolExecutor(
        3,3,0L,java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(96))
    private val logoLoading=mutableSetOf<String>()
    private val logoWaiting=mutableMapOf<String,MutableList<ImageView>>()
    private fun logoView(channel:Channel,size:Int=52,reuse:ImageView?=null):ImageView{
        val pixels=(size*resources.displayMetrics.density).toInt()
        val view=reuse ?: ImageView(this)
        view.layoutParams=LinearLayout.LayoutParams(pixels,pixels)
        view.scaleType=ImageView.ScaleType.FIT_CENTER
        view.setPadding(4,4,4,4)
        view.tag=null
        view.setImageResource(android.R.drawable.ic_menu_gallery)
        view.imageTintList=android.content.res.ColorStateList.valueOf(0xFFB6C9DD.toInt())
        val url=channel.logo.trim()
        if(!url.startsWith("http://") && !url.startsWith("https://"))return view
        view.tag=url
        val cached=logoCache.get(url)
        if(cached!=null){
            view.imageTintList=null
            view.setImageBitmap(cached)
            return view
        }
        val waiting=logoWaiting.getOrPut(url){mutableListOf()}
        if(waiting.size<24)waiting.add(view)
        if(!logoLoading.add(url))return view
        try{logoExecutor.execute{
            val bmp=try{
                val conn=URL(url).openConnection().apply{
                    connectTimeout=5000
                    readTimeout=5000
                    setRequestProperty("User-Agent","Mozilla/5.0 (Android TV; HomeTV)")
                }
                conn.getInputStream().use{stream->
                    // Decode a small logo rather than a full-size remote image.
                    val raw=android.graphics.BitmapFactory.decodeStream(stream)
                    if(raw!=null && (raw.width>256 || raw.height>256)){
                        val scale=minOf(256f/raw.width,256f/raw.height)
                        val small=android.graphics.Bitmap.createScaledBitmap(
                            raw,(raw.width*scale).toInt().coerceAtLeast(1),
                            (raw.height*scale).toInt().coerceAtLeast(1),true)
                        if(small!==raw)raw.recycle()
                        small
                    }else raw
                }
            }catch(_:Exception){null}
            runOnUiThread{
                logoLoading.remove(url)
                if(bmp!=null)logoCache.put(url,bmp)
                logoWaiting.remove(url)?.forEach{target->
                    if(target.tag==url && bmp!=null){
                        target.imageTintList=null
                        target.setImageBitmap(bmp)
                    }
                }
            }
        }}catch(_:java.util.concurrent.RejectedExecutionException){
            logoLoading.remove(url)
            logoWaiting.remove(url)
        }
        return view
    }
    private fun logoRow(channel:Channel,title:String,convertView:View?=null):LinearLayout{
        // Reuse the same ImageView and TextView while scrolling.
        val row=(convertView as? LinearLayout) ?: LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(8,5,8,5)
            addView(ImageView(this@MainActivity))
            addView(TextView(this@MainActivity).apply{
                textSize=17f
                setTextColor(android.graphics.Color.WHITE)
                setPadding(12,8,8,8)
                isSingleLine=true
                ellipsize=android.text.TextUtils.TruncateAt.END
            },LinearLayout.LayoutParams(0,-2,1f))
        }
        logoView(channel,52,row.getChildAt(0) as ImageView)
        (row.getChildAt(1) as TextView).text=title
        return row
    }
    private fun showOverlayList(searchQuery:String?=null){
        prefs.edit().putBoolean("favorites_surfing",false).apply()
        val screen=videoScreen ?:return
        if(channels.isEmpty())return
        screen.findViewWithTag<View>("channel_drawer")?.let{screen.removeView(it);return}
        screen.findViewWithTag<View>("settings_drawer")?.let{screen.removeView(it)}
        screen.findViewWithTag<View>("left_navigation")?.let{screen.removeView(it)}
        val drawer=LinearLayout(this).apply{
            tag="channel_drawer"
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(informationColor(0x15283C))
            isFocusableInTouchMode=true
        }
        drawer.addView(TextView(this).apply{
            text=(selectedPlaylistSource() ?: "ALL PLAYLISTS")+"  •  LEFT for Groups / Home"
            textSize=19f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(16,16,8,12)
        })
        drawer.addView(button(if(searchQuery==null)"⌕ Search channels" else "⌕ "+searchQuery+"  •  Clear / Change"){ 
            val input=EditText(this).apply{
                hint="Channel name or group"
                setSingleLine(true)
                setText(searchQuery ?: "")
                selectAll()
            }
            AlertDialog.Builder(this).setTitle("Search channels")
                .setView(input)
                .setPositiveButton("Search"){_,_->
                    val query=input.text.toString().trim().takeIf{it.isNotEmpty()}
                    if(drawer.parent===screen)screen.removeView(drawer)
                    showOverlayList(query)
                }.setNeutralButton("Clear"){_,_->
                    if(drawer.parent===screen)screen.removeView(drawer)
                    showOverlayList()
                }.setNegativeButton("Cancel",null).show()
        })
        val list=ListView(this)
        val available=playlistIndices().filter{
            !isGroupHidden(channels[it].group) &&
                (searchQuery==null || channels[it].name.contains(searchQuery,true) ||
                    channels[it].group.contains(searchQuery,true))
        }
        list.adapter=object:android.widget.BaseAdapter(){
            override fun getCount()=available.size
            override fun getItem(position:Int):Any=channels[available[position]]
            override fun getItemId(position:Int)=available[position].toLong()
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View=
                logoRow(channels[available[position]],channelLabel(available[position]),convertView)
        }
        drawer.addView(list,LinearLayout.LayoutParams(-1,0,1f))

        val width=(resources.displayMetrics.widthPixels*0.43f).toInt().coerceAtLeast(260)
        screen.addView(drawer,FrameLayout.LayoutParams(width,-1,Gravity.START))
        fun close(){if(drawer.parent===screen)screen.removeView(drawer);screen.requestFocus()}
        list.setOnItemClickListener{_,_,position,_->
            val selected=available[position]
            close()
            // Manual selection is permitted even for channels excluded from surfing.
            playIndex(preferredChannel(selected))
        }
        list.setOnItemLongClickListener{_,_,position,_->
            showChannelListQuickActions(available[position]){
                (list.adapter as android.widget.BaseAdapter).notifyDataSetChanged()
                list.setSelection(position)
                list.requestFocus()
            }
            true
        }
        list.setOnKeyListener{_,code,event->
            if(event.action==KeyEvent.ACTION_DOWN && code==KeyEvent.KEYCODE_DPAD_LEFT){
                close();showLeftNavigation();true
            }else if(event.action==KeyEvent.ACTION_DOWN && (code==KeyEvent.KEYCODE_BACK || code==KeyEvent.KEYCODE_DPAD_RIGHT)){
                close();true
            }else false
        }
        val playingPosition=available.indexOfFirst{it==active || (active in channels.indices && sameChannel(channels[it],channels[active]))}
        if(playingPosition>=0){
            list.choiceMode=android.widget.ListView.CHOICE_MODE_SINGLE
            list.setItemChecked(playingPosition,true)
            list.setSelection(playingPosition)
        }
        list.requestFocus()
    }
    private fun showLeftNavigation(){
        val screen=videoScreen ?:return
        screen.findViewWithTag<View>("channel_drawer")?.let{screen.removeView(it)}
        screen.findViewWithTag<View>("left_navigation")?.let{screen.removeView(it);return}
        val drawer=LinearLayout(this).apply{
            tag="left_navigation"
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(0xF015283C.toInt())
            isFocusableInTouchMode=true
        }
        drawer.addView(TextView(this).apply{
            text="HOMETV  •  "+(selectedPlaylistSource() ?: "ALL PLAYLISTS")+" GROUPS"
            textSize=20f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(20,18,12,16)
        })
        val groups=playlistIndices().map{channels[it].group}.distinct()
        val options=listOf("⚙  Settings","⌂  Home","★  Favorites","🙈  Hidden channels","✓  Check hidden channels","▤  EPG / TV Guide","↻  Refresh from source","☰  All channels","▦  Playlists")+
            groups.map{"▸  "+it}
        val list=ListView(this)
        list.adapter=android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,options)
        drawer.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        val width=(resources.displayMetrics.widthPixels*0.43f).toInt().coerceAtLeast(260)
        screen.addView(drawer,FrameLayout.LayoutParams(width,-1,Gravity.START))
        fun close(){if(drawer.parent===screen)screen.removeView(drawer);screen.requestFocus()}
        list.setOnItemLongClickListener{_,_,choice,_->
            val group=groups.getOrNull(choice-9)
            if(group!=null){close();groupOptions(group);true}else false
        }
        list.setOnItemClickListener{_,_,choice,_->
            close()
            when(choice){
                0->liveSettings()
                1->showHome()
                2->favorites()
                3->hiddenChannels()
                4->checkHiddenChannelsNow()
                5->showEpg()
                6->refresh({if(active>=0)showChannelInfo(active,"Playlist refresh finished") else showHome()},true)
                7->showOverlayList()
                8->showPlaylistDrawer()
                else->{
                    val group=groups.getOrNull(choice-9)
                    if(group!=null)showGroupDrawer(group)
                }
            }
        }
        list.setOnKeyListener{_,code,event->
            if(event.action==KeyEvent.ACTION_DOWN && (code==KeyEvent.KEYCODE_DPAD_RIGHT || code==KeyEvent.KEYCODE_BACK)){
                close();showOverlayList();true
            }else false
        }
        val currentGroup=channels.getOrNull(active)?.group
        val selectedGroup=groups.indexOf(currentGroup)
        if(selectedGroup>=0){
            list.setSelection(selectedGroup+9)
            list.setItemChecked(selectedGroup+9,true)
        }
        list.requestFocus()
    }
    private fun showPlaylistDrawer(){
        val screen=videoScreen ?:return
        screen.findViewWithTag<View>("channel_drawer")?.let{screen.removeView(it)}
        screen.findViewWithTag<View>("left_navigation")?.let{screen.removeView(it)}
        val sources=playlists().filter{it.enabled && channels.any{c->c.source==it.name}}
            .map{it.name}.distinct()
        val names=listOf("☰  All Playlists")+sources.map{"▸  "+it}
        val drawer=LinearLayout(this).apply{
            tag="left_navigation"
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(0xF015283C.toInt())
            isFocusableInTouchMode=true
        }
        drawer.addView(TextView(this).apply{
            text="HOMETV  •  PLAYLISTS"
            textSize=20f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(20,18,12,16)
        })
        val list=ListView(this)
        list.adapter=android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,names)
        drawer.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        screen.addView(drawer,FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels*0.43f).toInt().coerceAtLeast(260),-1,Gravity.START))
        fun close(){if(drawer.parent===screen)screen.removeView(drawer);screen.requestFocus()}
        list.setOnItemClickListener{_,_,position,_->
            val selected=if(position==0)null else sources.getOrNull(position-1)
            prefs.edit().apply{
                if(selected==null)remove("selected_playlist_source")
                else putString("selected_playlist_source",selected)
            }.apply()
            close()
            showOverlayList()
        }
        list.setOnKeyListener{_,code,event->
            if(event.action==KeyEvent.ACTION_DOWN &&
                (code==KeyEvent.KEYCODE_BACK || code==KeyEvent.KEYCODE_DPAD_RIGHT)){
                close();showLeftNavigation();true
            }else false
        }
        val selected=selectedPlaylistSource()
        list.setSelection(if(selected==null)0 else (sources.indexOf(selected)+1).coerceAtLeast(0))
        list.requestFocus()
    }
    private fun showGroupDrawer(group:String){
        prefs.edit().putBoolean("favorites_surfing",false).apply()
        val screen=videoScreen ?:return
        screen.findViewWithTag<View>("channel_drawer")?.let{screen.removeView(it)}
        val indices=playlistIndices().filter{channels[it].group==group}
        val drawer=LinearLayout(this).apply{
            tag="channel_drawer"
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(0xF015283C.toInt())
        }
        drawer.addView(TextView(this).apply{
            text=group+"  •  LEFT for groups"
            textSize=19f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(16,16,8,12)
        })
        val list=ListView(this)
        list.adapter=android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,indices.map{channelLabel(it)})
        drawer.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        screen.addView(drawer,FrameLayout.LayoutParams((resources.displayMetrics.widthPixels*0.43f).toInt().coerceAtLeast(260),-1,Gravity.START))
        fun close(){if(drawer.parent===screen)screen.removeView(drawer);screen.requestFocus()}
        list.setOnItemClickListener{_,_,position,_->close();playIndex(preferredChannel(indices[position]))}
        list.setOnItemLongClickListener{_,_,position,_->
            showChannelListQuickActions(indices[position]){
                (list.adapter as android.widget.BaseAdapter).notifyDataSetChanged()
                list.setSelection(position)
                list.requestFocus()
            }
            true
        }
        list.setOnKeyListener{_,code,event->
            if(event.action==KeyEvent.ACTION_DOWN && code==KeyEvent.KEYCODE_DPAD_LEFT){close();showLeftNavigation();true}
            else if(event.action==KeyEvent.ACTION_DOWN && (code==KeyEvent.KEYCODE_BACK || code==KeyEvent.KEYCODE_DPAD_RIGHT)){close();true}
            else false
        }
        val playingPosition=indices.indexOfFirst{it==active || (active in channels.indices && sameChannel(channels[it],channels[active]))}
        if(playingPosition>=0){
            list.choiceMode=android.widget.ListView.CHOICE_MODE_SINGLE
            list.setItemChecked(playingPosition,true)
            list.setSelection(playingPosition)
        }
        list.requestFocus()
    }
    private fun showPlaybackSettings(){
        val screen=videoScreen ?:return
        screen.findViewWithTag<View>("settings_drawer")?.let{screen.removeView(it);return}
        screen.findViewWithTag<View>("channel_drawer")?.let{screen.removeView(it)}
        val options=arrayOf(
            "Universal aspect ratio: "+(prefs.getString("screen_mode","Fit") ?: "Fit"),
            "This channel aspect ratio: "+screenMode(),
            "Picture quality","Buffering","Audio tracks","Subtitles","Sleep timer","Playback engine",
            "Date & time overlay","Alternative stream / URL","Home screen","Full settings"
        )
        val drawer=LinearLayout(this).apply{
            tag="settings_drawer"
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(0xF015283C.toInt())
            isFocusableInTouchMode=true
        }
        drawer.addView(TextView(this).apply{
            text="PLAYBACK SETTINGS"
            textSize=20f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(20,18,12,16)
        })
        val list=ListView(this)
        list.adapter=android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,options)
        drawer.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        val width=(resources.displayMetrics.widthPixels*0.43f).toInt().coerceAtLeast(260)
        screen.addView(drawer,FrameLayout.LayoutParams(width,-1,Gravity.END))
        fun close(){if(drawer.parent===screen)screen.removeView(drawer);screen.requestFocus()}
        list.setOnItemClickListener{_,_,choice,_->
            close()
            when(choice){
                0->selectScreenMode(null)
                1->selectScreenMode(active.takeIf{it>=0})
                2->selectQuality(active.takeIf{it>=0})
                3->chooseBufferProfile()
                4->audioTracks()
                5->subtitleTracks()
                6->sleepTimer()
                7->if(active>=0)chooseChannelEngine(active)
                8->clockSettings()
                9->selectAlternative(active)
                10->showHome()
                11->liveSettings()
            }
        }
        list.setOnKeyListener{_,code,event->
            if(event.action==KeyEvent.ACTION_DOWN && (code==KeyEvent.KEYCODE_BACK || code==KeyEvent.KEYCODE_DPAD_LEFT)){
                close();true
            }else false
        }
        list.requestFocus()
    }
    private val qualityModes=arrayOf("Auto","Data Saver (360p)","Standard (480p)","HD (720p)","Full HD (1080p)","Best Available")
    private fun qualityMode(index:Int?=active.takeIf{it>=0}):String{
        val url=index?.let{channels.getOrNull(it)?.url}
        if(url!=null)prefs.getString("quality_channel_"+hash(url),null)?.let{return it}
        return prefs.getString("quality_global","Auto") ?: "Auto"
    }
    private var qualityUpgradeTask:Runnable?=null
    private fun scheduleQualityUpgrade(){
        qualityUpgradeTask?.let{handler.removeCallbacks(it)}
        qualityUpgradeTask=null
        val exo=player ?:return
        // Best Available must remain highest quality; never silently revert it to Auto.
        if(qualityMode()!="Auto")return
        // Start Auto at a conservative adaptive cap; upgrade only after playback
        // has actually remained ready, never after a fixed elapsed wall-clock delay.
        exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE,480)
            .setForceHighestSupportedBitrate(false).build()
        val generation=playbackGeneration
        var stableSamples=0
        val task=object:Runnable{
            override fun run(){
                if(player!==exo || generation!=playbackGeneration || active<0 ||
                    qualityMode()!="Auto")return
                if(exo.playbackState==Player.STATE_READY && exo.isPlaying){
                    stableSamples++
                }else stableSamples=0
                if(stableSamples>=3){
                    exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon()
                        .setMaxVideoSize(Int.MAX_VALUE,Int.MAX_VALUE)
                        .setForceHighestSupportedBitrate(false).build()
                    qualityUpgradeTask=null
                }else handler.postDelayed(this,1000L)
            }
        }
        qualityUpgradeTask=task
        handler.postDelayed(task,1000L)
    }
    private fun applyQuality(){
        val exo=player ?:return
        val mode=qualityMode()
        val maxHeight=when(mode){
            "Data Saver (360p)"->360
            "Standard (480p)"->480
            "HD (720p)"->720
            "Full HD (1080p)"->1080
            else->Int.MAX_VALUE
        }
        val builder=exo.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE,maxHeight)
            .setForceHighestSupportedBitrate(mode=="Best Available")
        exo.trackSelectionParameters=builder.build()
    }
    private fun isYoutubeChannel(index:Int):Boolean{
        val host=try{channels.getOrNull(index)?.url?.let{Uri.parse(it).host?.lowercase(java.util.Locale.ROOT)}}catch(_:Exception){null}
        return host=="youtu.be" || host=="youtube.com" ||
            host?.endsWith(".youtube.com")==true || host=="youtube-nocookie.com"
    }
    private fun youtubeQualityPreference(index:Int):String{
        val url=channels.getOrNull(index)?.url ?:return "Best available"
        return prefs.getString("youtube_quality_"+hash(url),"Best available") ?: "Best available"
    }
    private fun requestYoutubeEnhancedQuality(viewer:WebView,index:Int,screen:FrameLayout){
        val preference=youtubeQualityPreference(index)
        if(preference=="Auto")return
        // Restore V7's HD quality selection. Apply once, not three times.
        // Only reduce quality after repeated real buffering; never change the
        // saved user preference or any Media3 / LibVLC playback settings.
        val wanted=if(preference=="720p") "hd720" else "hd1080"
        val qualityGeneration=playbackGeneration
        handler.postDelayed({
            if(web===viewer && active==index && videoScreen===screen && playbackGeneration==qualityGeneration){
                viewer.evaluateJavascript("""(function(){
                    try{
                        var p=document.getElementById('movie_player');
                        var v=document.querySelector('video');
                        if(!p || !v || v.paused)return 'player-not-ready';
                        var levels=typeof p.getAvailableQualityLevels==='function'
                            ? p.getAvailableQualityLevels():[];
                        var preferred=${JSONObject.quote(wanted)};
                        var fallback=['hd1080','hd720','large','medium'];
                        var chosen=levels.indexOf(preferred)>=0?preferred:
                            fallback.find(function(q){return levels.indexOf(q)>=0;});
                        if(!chosen)return 'no-supported-quality';
                        if(typeof p.setPlaybackQualityRange==='function')
                            p.setPlaybackQualityRange(chosen,chosen);
                        if(typeof p.setPlaybackQuality==='function')
                            p.setPlaybackQuality(chosen);
                        // Count buffering episodes in a rolling 45-second window.
                        // Ignore initial startup; only step down once after
                        // three distinct stalls so normal 1080p remains sharp.
                        if(!v.__homeTvQualityWatch){
                            v.__homeTvQualityWatch=true;
                            var started=Date.now(),events=[],lowered=false;
                            v.addEventListener('waiting',function(){
                                if(lowered || Date.now()-started<12000 || v.paused || v.ended)return;
                                var now=Date.now();
                                events=events.filter(function(t){return now-t<45000;});
                                if(events.length && now-events[events.length-1]<2500)return;
                                events.push(now);
                                if(events.length<3)return;
                                var q=chosen==='hd1080'?'hd720':'large';
                                if(levels.indexOf(q)<0){q='auto';}
                                try{
                                    if(typeof p.setPlaybackQualityRange==='function')
                                        p.setPlaybackQualityRange(q==='auto'?'tiny':q,q==='auto'?'hd1080':q);
                                    if(q!=='auto' && typeof p.setPlaybackQuality==='function')
                                        p.setPlaybackQuality(q);
                                    lowered=true;
                                }catch(e){}
                            },{passive:true});
                        }
                        return 'hd-selected-'+chosen;
                    }catch(e){return 'quality-api-unavailable';}
                })();""",null)
            }
        },3000L)
    }
    private fun showYoutubeQualityDiagnostics(){
        val viewer=web
        if(viewer==null || !isYoutubeChannel(active)){
            showBanner("Open a YouTube channel first",true)
            return
        }
        viewer.evaluateJavascript("""(function(){
            var v=document.querySelector('video');
            if(!v)return JSON.stringify({error:'YouTube video is still loading'});
            var p=document.getElementById('movie_player');
            var q='Unavailable',levels=[];
            try{
                if(p && typeof p.getPlaybackQuality==='function')q=p.getPlaybackQuality();
                if(p && typeof p.getAvailableQualityLevels==='function')levels=p.getAvailableQualityLevels();
            }catch(e){}
            return JSON.stringify({width:v.videoWidth,height:v.videoHeight,
                displayWidth:Math.round(v.getBoundingClientRect().width),
                displayHeight:Math.round(v.getBoundingClientRect().height),
                quality:q,available:levels});
        })();"""){raw->
            try{
                val decoded=org.json.JSONTokener(raw).nextValue() as? String ?:raw
                val info=JSONObject(decoded)
                val message=if(info.has("error"))info.optString("error") else
                    "Decoded video: "+info.optInt("width")+" × "+info.optInt("height")+
                    "\\nPlayer size: "+info.optInt("displayWidth")+" × "+info.optInt("displayHeight")+
                    "\\nYouTube quality: "+info.optString("quality","Unavailable")+
                    "\\nAvailable: "+info.optJSONArray("available")?.toString()
                AlertDialog.Builder(this).setTitle("YouTube picture diagnostics")
                    .setMessage(message)
                    .setPositiveButton("OK",null).show()
            }catch(e:Exception){
                AlertDialog.Builder(this).setTitle("YouTube picture diagnostics")
                    .setMessage("YouTube has not exposed playback resolution yet. Try again after the video starts.")
                    .setPositiveButton("OK",null).show()
            }
        }
    }
    private fun selectQuality(index:Int?=null){
        if(index!=null && isYoutubeChannel(index)){
            AlertDialog.Builder(this).setTitle("YouTube picture quality")
                .setItems(arrayOf("Best available (experimental)","Prefer 1080p","Prefer 720p","Auto (YouTube controls resolution)","Show actual resolution / quality")){
                    _,choice->
                    if(choice==4)showYoutubeQualityDiagnostics()
                    else{
                        val mode=arrayOf("Best available","1080p","720p","Auto")[choice]
                        val url=channels[index].url
                        prefs.edit().putString("youtube_quality_"+hash(url),mode).apply()
                        showBanner("YouTube quality: "+mode+" — reopening channel applies the preference")
                    }
                }.setNegativeButton("Cancel",null).show()
            return
        }
        val url=index?.let{channels.getOrNull(it)?.url}
        val key=url?.let{"quality_channel_"+hash(it)}
        val options=if(key!=null)arrayOf("Use universal default")+qualityModes else qualityModes
        val current=if(key!=null)prefs.getString(key,null) else prefs.getString("quality_global","Auto")
        val selected=if(key!=null && current==null)0 else options.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(if(index!=null)numberFor(index).toString()+". "+channels[index].name+" Settings — Picture Quality" else "Universal picture quality")
            .setSingleChoiceItems(options,selected){dialog,choice->
                val editor=prefs.edit()
                if(key!=null){
                    if(choice==0)editor.remove(key) else editor.putString(key,options[choice])
                }else{
                    editor.putString("quality_global",options[choice])
                    prefs.all.keys.filter{it.startsWith("quality_channel_")}.forEach{editor.remove(it)}
                }
                editor.apply()
                applyQuality()
                dialog.dismiss()
                if(active>=0)showBanner("Quality: "+qualityMode()) else if(index==null)settings()
            }.setNegativeButton("Cancel",null).show()
    }
    private val screenModes=arrayOf("Fit","Fill / Crop","Stretch","16:9","4:3","21:9","1:1","Original","Zoom 110%","Zoom 125%","Zoom 150%")
    private var previewScreenMode:String?=null
    private fun screenMode():String{
        previewScreenMode?.let{return it}
        val url=channels.getOrNull(active)?.url
        if(url!=null){
            val saved=prefs.getString("screen_channel_"+hash(url),null)
            if(saved!=null)return saved
        }
        return prefs.getString("screen_mode","Fit") ?: "Fit"
    }
    private fun resizeMode():Int=when(screenMode()){
        "Stretch"->androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        "Fill / Crop","Zoom 110%","Zoom 125%","Zoom 150%"->androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else->androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    private fun prepareVideoView(view:PlayerView){
        val mode=screenMode()
        view.resizeMode=resizeMode()
        view.setBackgroundColor(android.graphics.Color.BLACK)
        val ratio=when(mode){
            "16:9"->16f/9f
            "4:3"->4f/3f
            "21:9"->21f/9f
            "1:1"->1f
            else->0f
        }
        if(ratio>0f){
            view.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)?.setAspectRatio(ratio)
        }
    }
    private fun applyVideoSize(){
        val mode=screenMode()
        val forcedRatio=when(mode){
            "16:9"->16f/9f
            "4:3"->4f/3f
            "21:9"->21f/9f
            "1:1"->1f
            else->0f
        }
        currentPlayerView?.let{view->
            view.resizeMode=resizeMode()
            val frame=view.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            val format=player?.videoFormat
            val natural=if(format!=null && format.height>0)
                format.width.toFloat()*format.pixelWidthHeightRatio/format.height else 0f
            // Preserve a user-selected ratio even when Media3 updates its video dimensions.
            if(forcedRatio>0f){
                frame?.setAspectRatio(forcedRatio)
            }else if(natural>0f){
                frame?.setAspectRatio(natural)
            }
            // The content frame handles fit, crop and stretch; avoid double-scaling.
            view.videoSurfaceView?.let{surface->
                val zoom=when(mode){
                    "Zoom 110%"->1.10f
                    "Zoom 125%"->1.25f
                    "Zoom 150%"->1.50f
                    else->1f
                }
                surface.scaleX=zoom
                surface.scaleY=zoom
            }
        }
        ijkSurface?.let{surface->
            val displayWidth=(videoScreen?.width ?: 0).takeIf{it>0} ?: resources.displayMetrics.widthPixels
            val displayHeight=(videoScreen?.height ?: 0).takeIf{it>0} ?: resources.displayMetrics.heightPixels
            val sourceRatio=if(ijkVideoWidth>0 && ijkVideoHeight>0)ijkVideoWidth.toFloat()/ijkVideoHeight else 16f/9f
            val targetRatio=if(forcedRatio>0f)forcedRatio else sourceRatio
            val fill=mode=="Fill / Crop" || mode=="Stretch"
            val zoom=when(mode){"Zoom 110%"->1.10f;"Zoom 125%"->1.25f;"Zoom 150%"->1.50f;else->1f}
            val width=if(fill)displayWidth else minOf(displayWidth,(displayHeight*targetRatio).toInt()).coerceAtLeast(1)
            val height=if(fill)displayHeight else minOf(displayHeight,(displayWidth/targetRatio).toInt()).coerceAtLeast(1)
            val lp=FrameLayout.LayoutParams(width,height,android.view.Gravity.CENTER)
            surface.layoutParams=lp
            surface.scaleX=zoom
            surface.scaleY=zoom
        }
        vlcPlayer?.let{vlc->
            try{
                val displayRatio=resources.displayMetrics.let{if(it.heightPixels>0)it.widthPixels.toFloat()/it.heightPixels else 16f/9f}
                val videoRatio=vlc.currentVideoTrack?.let{if(it.height>0)it.width.toFloat()/it.height else 0f} ?: 0f
                val cropRatio=if(videoRatio>0f)videoRatio else displayRatio
                vlc.setScale(0f)
                vlc.setAspectRatio(when(mode){
                    "16:9"->"16:9"
                    "4:3"->"4:3"
                    "21:9"->"21:9"
                    "1:1"->"1:1"
                    "Stretch"->resources.displayMetrics.let{"${it.widthPixels}:${it.heightPixels}"}
                    else->null
                })
                val scale=when(mode){
                    "Original"->1f
                    "Zoom 110%"->1.10f
                    "Zoom 125%"->1.25f
                    "Zoom 150%"->1.50f
                    "Fill / Crop"->if(cropRatio>0f && displayRatio>0f)maxOf(displayRatio/cropRatio,cropRatio/displayRatio) else 1f
                    else->0f
                }
                if(scale>0f)vlc.setScale(scale)
            }catch(e:Exception){android.util.Log.w("HomeTV","Video size adjustment",e)}
        }
    }
    private fun selectScreenMode(channelIndex:Int?=null){
        val target=channelIndex?.takeIf{it in channels.indices}
        val key=if(target!=null)"screen_channel_"+hash(channels[target].url) else "screen_mode"
        val original=prefs.getString(key,null)
        val universal=prefs.getString("screen_mode","Fit") ?: "Fit"
        val labels=if(target!=null)arrayOf("Use universal default")+screenModes else screenModes
        val selected=if(target!=null && original==null)0 else labels.indexOf(original ?: universal).coerceAtLeast(0)
        var pending=selected
        var confirmed=false
        fun preview(position:Int){
            pending=position.coerceIn(0,labels.lastIndex)
            previewScreenMode=if(target!=null && pending==0)universal else labels[pending]
            applyVideoSize()
        }
        fun commit(){
            confirmed=true
            val editor=prefs.edit()
            if(target!=null && pending==0)editor.remove(key)
            else editor.putString(key,labels[pending])
            if(target==null){
                // Universal change wins for aspect ratio; unrelated per-channel settings stay intact.
                prefs.all.keys.filter{it.startsWith("screen_channel_")}.forEach{editor.remove(it)}
            }
            editor.apply()
        }
        val dialog=AlertDialog.Builder(this)
            .setTitle(if(target!=null)numberFor(target).toString()+". "+channels[target].name+" Settings — Aspect Ratio" else "Universal aspect ratio")
            .setSingleChoiceItems(labels,selected){d,position->
                preview(position)
                // Android TV remote OK on a list row is a completed selection.
                commit()
                d.dismiss()
            }
            .setPositiveButton("Apply"){_,_->commit()}
            .setNegativeButton("Cancel",null).create()
        dialog.setOnDismissListener{
            previewScreenMode=null
            applyVideoSize()
            if(confirmed && active>=0)showBanner("Screen: "+screenMode())
        }
        dialog.setOnShowListener{
            val list=dialog.listView ?:return@setOnShowListener
            list.choiceMode=android.widget.ListView.CHOICE_MODE_SINGLE
            list.setItemChecked(selected,true)
            list.setSelection(selected)
            list.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){
                    list.setItemChecked(position,true)
                    preview(position)
                }
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            }
            // Do not consume DPAD_CENTER: ListView must dispatch its normal click,
            // which commits the previewed ratio and closes the menu.
            list.requestFocus()
        }
        showSideDialog(dialog)
    }
    private fun toggleScreenMode(){
        val next=if(screenMode()=="Fill / Crop")"Fit" else "Fill / Crop"
        val url=channels.getOrNull(active)?.url
        if(url!=null)prefs.edit().putString("screen_channel_"+hash(url),next).apply()
        else prefs.edit().putString("screen_mode",next).apply()
        applyVideoSize()
        if(active>=0)showBanner("Screen: "+next)
    }
    private fun returnToPreviousChannel(){
        val url=previousChannelUrl ?:run{showBanner("No previous channel");return}
        val index=channels.indexOfFirst{it.url==url && (previousChannelIdentity==null || id(it)==previousChannelIdentity)}
        if(index>=0 && index!=active)playIndex(index)
        else showBanner("Previous channel is unavailable")
    }

    // My Home TV: dedicated website-video controls. No changes to YouTube or native players.
    private fun isWebTv(c:Channel):Boolean{
        val u=try{Uri.parse(c.url)}catch(_:Exception){return false}
        val path=(u.path ?: "").lowercase(java.util.Locale.ROOT)
        if(path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".mp4") || path.endsWith(".ts"))return false
        return (u.scheme=="http" || u.scheme=="https") &&
            (c.mediaType=="web" || (c.webChannel.isNotBlank() && !c.webChannel.equals("no",true)))
    }
    private fun webTvJavascript():String = """(function(){
        try {
          if(!document.getElementById('myhometv-web-style')){
            var style=document.createElement('style');style.id='myhometv-web-style';
            style.textContent='html,body{background:#000!important;margin:0!important}video{object-fit:contain!important;max-width:100vw!important;max-height:100vh!important}';
            document.head.appendChild(style);
          }
          var videos=[].slice.call(document.querySelectorAll('video'));
          var frames=[].slice.call(document.querySelectorAll('iframe')).filter(function(f){
             var r=f.getBoundingClientRect();return r.width>180&&r.height>100;
          });
          var player=videos.find(function(v){return v.videoWidth>0||v.readyState>0;})||videos[0];
          if(!player && !window.__myHomeTvAutoClicked){
            var buttons=[].slice.call(document.querySelectorAll('button,[role="button"],a[href],.play-button,.vjs-big-play-button'));
            var start=buttons.find(function(el){
              var text=(el.getAttribute('aria-label')||el.title||el.innerText||'').trim();
              return /^(play|watch live|watch now|start stream|play video)$/i.test(text) ||
                     el.classList.contains('vjs-big-play-button');
            });
            if(start){window.__myHomeTvAutoClicked=true;try{start.click()}catch(e){}}
          }
          if(player){
            player.controls=true;player.muted=false;player.volume=1;
            player.setAttribute('playsinline','true');
            var play=player.play();if(play&&play.catch)play.catch(function(){});
          }
          var primary=player||frames.sort(function(a,b){
            var x=a.getBoundingClientRect(),y=b.getBoundingClientRect();
            return y.width*y.height-x.width*x.height;
          })[0];
          if(primary){
            var container=primary;
            if(primary.tagName==='VIDEO' && primary.parentElement &&
                primary.parentElement.getBoundingClientRect().width>primary.getBoundingClientRect().width)
                container=primary.parentElement;
            if(!container.dataset.myhometvExpanded){
              container.dataset.myhometvExpanded='1';
              container.style.setProperty('position','fixed','important');
              container.style.setProperty('inset','0','important');
              container.style.setProperty('width','100vw','important');
              container.style.setProperty('height','100vh','important');
              container.style.setProperty('max-width','100vw','important');
              container.style.setProperty('max-height','100vh','important');
              container.style.setProperty('z-index','2147483000','important');
              container.style.setProperty('background','#000','important');
              primary.style.setProperty('width','100%','important');
              primary.style.setProperty('height','100%','important');
            }
          }
          var candidates=[];
          var seen={};
          [].slice.call(document.querySelectorAll('a[href],button,[role="button"],[onclick]')).forEach(function(el,i){
            var label=(el.innerText||el.textContent||el.getAttribute('aria-label')||'').replace(/\s+/g,' ').trim().slice(0,65);
            var href=el.href||el.getAttribute('data-url')||'';
            if(label.length<2||label.length>65)return;
            var relevant=/tv|live|channel|watch|play|stream|hd|sd/i.test(label)||
                         /\/live\/|\.m3u8|\.mpd|embed\//i.test(href);
            if(!relevant)return;
            var key=label.toLowerCase()+'|'+href;
            if(seen[key])return;seen[key]=1;
            candidates.push({name:label,url:href,index:i});
          });
          [].slice.call(document.querySelectorAll('video source[src],video[src],iframe[src]')).forEach(function(el){
            var raw=el.src||'';
            if(raw.indexOf('https://')!==0 && raw.indexOf('http://')!==0)return;
            if(candidates.some(function(c){return c.url===raw;}))return;
            candidates.push({name:el.tagName==='IFRAME'?'Embedded player':'Direct video stream',url:raw,index:-1});
          });
          window.__myHomeTvLinks=candidates.slice(0,70);
          return JSON.stringify({videos:videos.length,frames:frames.length,
             playing:!!(player&&!player.paused),candidates:window.__myHomeTvLinks});
        }catch(e){return JSON.stringify({error:String(e)})}
    })();"""
    private fun inspectWebTv(viewer:WebView,index:Int,screen:FrameLayout,showMenu:Boolean=false){
        if(web!==viewer || active!=index || videoScreen!==screen)return
        viewer.evaluateJavascript(webTvJavascript()){raw->
            if(web!==viewer || active!=index || videoScreen!==screen)return@evaluateJavascript
            try{
                val decoded=org.json.JSONTokener(raw).nextValue() as? String ?: raw
                val data=JSONObject(decoded)
                if(showMenu){
                    val candidates=data.optJSONArray("candidates") ?: org.json.JSONArray()
                    val labels=mutableListOf<String>()
                    val urls=mutableListOf<String>()
                    for(i in 0 until candidates.length()){
                        val item=candidates.optJSONObject(i) ?:continue
                        labels.add(item.optString("name","Channel "+(i+1)))
                        urls.add(item.optString("url",""))
                    }
                    val options=arrayOf("Retry / fullscreen video","Open original webpage","Save current website as TV channel")+
                        labels.map{"Channel: "+it}
                    AlertDialog.Builder(this).setTitle("Web TV • "+channels[index].name)
                        .setItems(options){_,choice->
                            when(choice){
                                0->inspectWebTv(viewer,index,screen,false)
                                1->{
                                    prefs.edit().remove("web_preferred_"+channels[index].url.hashCode()).apply()
                                    viewer.loadUrl(channels[index].url)
                                }
                                2->saveWebTvChannel(channels[index].name,channels[index].url)
                                else->{
                                    val n=choice-3
                                    if(n in urls.indices){
                                        val target=urls[n]
                                        if(target.startsWith("https://")||target.startsWith("http://")){
                                            prefs.edit().putString("web_preferred_"+channels[index].url.hashCode(),target).apply()
                                            viewer.loadUrl(target)
                                        }else{
                                            val js="(function(){var a=window.__myHomeTvLinks||[];var c=a["+n+"];if(!c)return;var elements=[].slice.call(document.querySelectorAll('a[href],button,[role=button],[onclick]'));var el=elements[c.index];if(el)el.click();})();"
                                            viewer.evaluateJavascript(js,null)
                                            handler.postDelayed({inspectWebTv(viewer,index,screen,false)},1600L)
                                        }
                                    }
                                }
                            }
                        }.setNegativeButton("Cancel",null).show()
                }else if(data.optInt("videos")==0 && data.optInt("frames")==0){
                    showBanner("No embedded video detected. Press MENU for web channels.",true)
                }
            }catch(e:Exception){
                if(showMenu)showBanner("Web player menu unavailable: "+e.message,true)
            }
        }
    }
    private fun saveWebTvChannel(name:String,url:String){
        if(!url.startsWith("http://") && !url.startsWith("https://"))return
        val array=try{org.json.JSONArray(prefs.getString("myhometv_saved_web","[]"))}catch(_:Exception){org.json.JSONArray()}
        for(i in 0 until array.length())if(array.optJSONObject(i)?.optString("url")==url){
            showBanner("Already saved: "+name);return
        }
        val obj=JSONObject().put("name",name).put("url",url)
        array.put(obj)
        prefs.edit().putString("myhometv_saved_web",array.toString()).apply()
        if(channels.none{it.url==url}){
            channels.add(Channel(name,url,"Web TV",webChannel="auto"))
        }
        showBanner("Saved web channel: "+name)
    }
    private fun addSavedWebTv(out:MutableList<Channel>){
        val array=try{org.json.JSONArray(prefs.getString("myhometv_saved_web","[]"))}catch(_:Exception){return}
        for(i in 0 until array.length()){
            val obj=array.optJSONObject(i)?:continue
            val url=obj.optString("url")
            if((url.startsWith("https://")||url.startsWith("http://")) && out.none{it.url==url})
                out.add(Channel(obj.optString("name","Web TV"),url,"Web TV",webChannel="auto"))
        }
    }

    private fun playIndex(index:Int){
        // No cached or delayed playback callback may bypass license validation.
        if(!licenseReady)return
        if(index !in channels.indices)return
        val c=channels[index]
        if(unlockedChannel!=id(c) && isLocked(c)){
            requirePin {unlockedChannel=id(c);playIndex(index)}
            return
        }
        // Keep channel changes independent of optional background probes.
        playbackGeneration++
        autoBufferStableSince=0L
        startupWatchdog?.let{handler.removeCallbacks(it)}
        startupWatchdog=null
        if(active in channels.indices && active!=index && !sameChannel(channels[active],c)){
            previousChannelUrl=channels[active].url
            previousChannelIdentity=id(channels[active])
        }
        val sessionKey=channelKey(c)
        if(alternativeSessionKey!=sessionKey){alternativeAttempted.clear();alternativeSessionKey=sessionKey;engineAttempts.clear();fallbackEngineOverride=null;recoveryPrompted.clear()}
        if(engineChannelUrl!=c.url){vlcAttempted=false;engineChannelUrl=c.url}
        if(retryUrl!=c.url)retryCount=0
        retryUrl=c.url
        stopPlayback()
        active=index
        if(unlockedChannel!=id(c)) unlockedChannel=null
        prefs.edit().putString("last_url",c.url).apply()
        rememberRecentChannel(c)
        screenInfoOnStart(index)
        window.decorView.systemUiVisibility=(View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        val screen=FrameLayout(this).apply{setBackgroundColor(android.graphics.Color.BLACK);isFocusableInTouchMode=true}
        videoScreen=screen
        setContentView(screen)
        // Alternative probing is initiated only after a sustained playback failure.
        val youtubeUri=try{
            val uri=Uri.parse(c.url)
            val host=uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.")
            if(host=="youtube.com" || host=="youtube-nocookie.com" || host=="youtu.be"){
                val path=uri.path ?: ""
                val id=if(host=="youtu.be")path.trim('/').substringBefore('/')
                    else when{
                        path=="/watch"->uri.getQueryParameter("v")
                        path.startsWith("/live/") || path.startsWith("/embed/") || path.startsWith("/shorts/")->path.trim('/').substringAfter('/').substringBefore('/')
                        else->null
                    }
                if(id!=null && id.matches(Regex("[A-Za-z0-9_-]{11}")))Uri.parse("https://www.youtube.com/watch?v="+id)
                else if(path.startsWith("/channel/") || path.startsWith("/@") || path.startsWith("/playlist"))uri
                else null
            }else null
        }catch(_:Exception){null}
        val webPage=try{
            val u=Uri.parse(c.url)
            val h=u.host?.lowercase()?.removePrefix("www.") ?: ""
            (u.scheme=="http" || u.scheme=="https") &&
                isWebTv(c)
        }catch(_:Exception){false}
        // Restore the original WebView watch-page route for YouTube.
        // Native stream scraping and embed mode are not reliable substitutes here.
        if(youtubeUri!=null || webPage){
            // Use the original YouTube watch page inside WebView.
            // Keep regular IPTV streams on their existing Media3/LibVLC path.
            val viewer=WebView(this)
            web=viewer
            viewer.setBackgroundColor(android.graphics.Color.BLACK)
            viewer.settings.javaScriptEnabled=true
            viewer.settings.domStorageEnabled=true
            viewer.settings.mediaPlaybackRequiresUserGesture=false
            viewer.settings.useWideViewPort=true
            viewer.settings.loadWithOverviewMode=true
            viewer.settings.setSupportZoom(false)
            // Keep the system WebView user agent unchanged; spoofing a desktop browser can cause YouTube verification challenges.
            viewer.webChromeClient=object:WebChromeClient(){
                private var fullscreenView:View?=null
                override fun onShowCustomView(view:View,callback:CustomViewCallback){
                    fullscreenView=view
                    screen.addView(view,FrameLayout.LayoutParams(-1,-1))
                    viewer.visibility=View.GONE
                    screen.findViewWithTag<View>("clock_overlay")?.bringToFront()
                }
                override fun onHideCustomView(){
                    fullscreenView?.let{screen.removeView(it)}
                    fullscreenView=null
                    viewer.visibility=View.VISIBLE
                    screen.findViewWithTag<View>("clock_overlay")?.bringToFront()
                }
            }
            viewer.webViewClient=object:WebViewClient(){
                override fun onPageFinished(view:WebView,url:String){
                    super.onPageFinished(view,url)
                    // Expand the YouTube video surface to fill the TV display.
                    // CSS layout avoids browser Fullscreen API gesture restrictions.
                    if(youtubeUri!=null && web===view && active==index && videoScreen===screen){
                        val fullScreenCss = """(function(){
                            if(document.getElementById('hometv-tv-fullscreen'))return;
                            var style=document.createElement('style');
                            style.id='hometv-tv-fullscreen';
                            style.textContent='html,body{margin:0!important;padding:0!important;overflow:hidden!important;background:#000!important;width:100%!important;height:100%!important}'+
                              'ytd-app,ytd-watch-flexy,#page-manager,#player,#full-bleed-container,#player-container-outer,#player-container-inner{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;max-width:100vw!important;max-height:100vh!important;margin:0!important;padding:0!important;background:#000!important}'+
                              '#movie_player,.html5-video-player,.html5-video-container{width:100%!important;height:100%!important;max-width:100%!important;max-height:100%!important;background:#000!important}'+
                              'video{object-fit:contain!important}'+
                              '#masthead-container,#secondary,#below,#comments,#related,#chat,#guide,#guide-button,ytd-mini-guide-renderer{display:none!important}'+
                              // YouTube's mobile watch page can add its own top navigation
                              // (logo, Open App, search) above the video, covering mute.
                              // Hide the page chrome only; preserve player playback controls.
                              'ytm-mobile-topbar-renderer,ytm-header-bar,ytm-pivot-bar-renderer,ytm-open-app-button,'+
                              '.mobile-topbar-header,.mobile-topbar,.ytm-mobile-topbar-renderer,'+
                              '.mobile-topbar-header-content,.mobile-topbar-logo,.open-app-button,'+
                              '.ytp-youtube-button,.ytp-impression-link,.ytp-watermark,.ytp-title-link,.ytp-title-channel,'+
                              '.ytp-chrome-top .ytp-title,.ytp-pause-overlay,.ytp-app-promo,'+
                              '.ytp-remote-button,.ytp-cards-button,.ytp-ce-element,.ytp-paid-content-overlay,'+
                              'ytm-topbar-menu-button-renderer,ytm-topbar-logo-renderer,'+
                              'ytm-companion-slot-renderer,.ytm-open-app-button,.ytp-open-app-button,'+
                              '.ytp-watch-later-button,.ytp-share-button{display:none!important;pointer-events:none!important}'+
                              '.ytp-mute-button{display:inline-flex!important;visibility:visible!important;pointer-events:auto!important}'+
                              '.ytp-chrome-top .ytp-title-container,.ytp-chrome-top .ytp-title-text{display:none!important}';
                            (document.head||document.documentElement).appendChild(style);
                        })();"""
                        view.evaluateJavascript(fullScreenCss,null)
                        requestYoutubeStartupAudio(view,index,screen)
                        requestYoutubeEnhancedQuality(view,index,screen)
                        beginYoutubeProgressTracking(view,index,screen)
                    }
                }
                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){
                    if(request.isForMainFrame && web===view){
                        android.util.Log.w("HomeTV","YouTube page error: "+error.description)
                        showBanner("YouTube connection issue. Remote and channel controls remain available.",true)
                    }
                }
                override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                    val target=request.url
                    return if(target.scheme=="http" || target.scheme=="https")false
                    else true
                }
            }
            screen.addView(viewer,FrameLayout.LayoutParams(-1,-1))
            // Do not overlay HomeTV controls on YouTube's mute/play buttons.
            // The remote BACK and channel keys remain handled by the Activity.
            if(webPage){
                // Website TV mode: keep cookies, JavaScript and iframe media support.
                viewer.settings.javaScriptCanOpenWindowsAutomatically=false
                viewer.settings.setSupportMultipleWindows(false)
                viewer.settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(viewer,true)
                viewer.webViewClient=object:WebViewClient(){
                    override fun onPageFinished(view:WebView,url:String){
                        super.onPageFinished(view,url)
                        if(web===view && active==index && videoScreen===screen){
                            longArrayOf(1100L,3000L,6500L).forEach{delay->
                                handler.postDelayed({inspectWebTv(view,index,screen,false)},delay)
                            }
                        }
                    }
                    override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){
                        if(request.isForMainFrame && web===view)
                            showBanner("Web TV page failed: "+error.description,true)
                    }
                    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest):Boolean{
                        return request.url.scheme !in listOf("http","https")
                    }
                }
                viewer.setOnKeyListener{_,key,event->
                    if(event.action==KeyEvent.ACTION_DOWN && (key==KeyEvent.KEYCODE_MENU ||
                        key==KeyEvent.KEYCODE_INFO)){
                        inspectWebTv(viewer,index,screen,true);true
                    }else false
                }
                val preferred=prefs.getString("web_preferred_"+c.url.hashCode(),"").orEmpty()
                viewer.loadUrl(if(preferred.startsWith("https://") || preferred.startsWith("http://"))preferred else c.url)
            }else{
                val yt=youtubeUri!!
                viewer.loadUrl(if(yt.path?.startsWith("/embed/")==true) yt.toString() else yt.buildUpon().appendQueryParameter("autoplay","1").build().toString())
            }
            viewer.requestFocus()
            if(c.mediaType in setOf("movie","series","music") && youtubeUri!=null){
                val seconds=requestedMediaResumeSeconds
                requestedMediaResumeSeconds=0L
                if(seconds>0L){
                    // YouTube watch URLs support a time parameter, but WebView seeking is best-effort.
                    viewer.loadUrl(youtubeUri.buildUpon().appendQueryParameter("t",seconds.toString()+"s").appendQueryParameter("autoplay","1").build().toString())
                }
            }
        }else if(engineFor(c)=="IJKPlayer"){
            startIjk(c,index,screen)
        }else if(engineFor(c)=="LibVLC"){
            startVlc(c,index,screen)
        }else{
            val factory=DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(6500)
                .setReadTimeoutMs(8500)
                .setUserAgent(c.headers["User-Agent"] ?: "VLC/3.0.20 LibVLC/3.0.20")
                .setDefaultRequestProperties(c.headers.filterKeys{!it.equals("User-Agent",true)})
            val buffer=bufferValues()
            val loadControl=DefaultLoadControl.Builder()
                .setBufferDurationsMs(buffer[0],buffer[1],buffer[2],buffer[3]).build()
            val exo=ExoPlayer.Builder(this, DefaultRenderersFactory(this)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).setLoadControl(loadControl).build()
            exo.setAudioAttributes(AudioAttributes.DEFAULT,true)
            exo.volume=1f
            player=exo
            applyQuality()
            applySubtitlePreference(c)
            val view=PlayerView(this).apply{
                player=exo
                useController=false
                resizeMode=resizeMode()
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            currentPlayerView=view
            prepareVideoView(view)
            screen.addView(view,FrameLayout.LayoutParams(-1,-1))
            applyVideoSize()
            val startupTimeout=Runnable{
                if(active==index && videoScreen===screen && player===exo &&
                    exo.playbackState!=Player.STATE_READY){
                    android.util.Log.w("HomeTV","Stream startup timeout: "+c.name)
                    handleEngineFailure(index,screen)
                }
            }
            startupWatchdog=startupTimeout
            handler.postDelayed(startupTimeout,6000L)
            var wasReady=false
            var bufferingRecoveryTask:Runnable?=null
            exo.addListener(object:Player.Listener{
                override fun onPlaybackStateChanged(state:Int){
                    if(state==Player.STATE_BUFFERING){
                        autoBufferStableSince=0L
                        autoBufferStalled()
                        if(wasReady){
                            bufferingRecoveryTask?.let{handler.removeCallbacks(it)}
                            val recovery=Runnable{
                                if(active==index && videoScreen===screen && player===exo &&
                                    exo.playbackState==Player.STATE_BUFFERING){
                                    handleEngineFailure(index,screen)
                                }
                            }
                            bufferingRecoveryTask=recovery
                            handler.postDelayed(recovery,30000L)
                        }
                    }else{
                        bufferingRecoveryTask?.let{handler.removeCallbacks(it)}
                        bufferingRecoveryTask=null
                    }
                    if(state==Player.STATE_READY){
                        wasReady=true
                        autoBufferHealthy()
                        retryCount=0
                        handler.postDelayed({
                            if(player===exo && active==index && exo.playbackState==Player.STATE_READY)
                                recoveryAttempts.remove(c.url)
                        },60000L)
                        rememberWorking(c)
                        startupWatchdog?.let{handler.removeCallbacks(it)}
                        startupWatchdog=null
                        applyVideoSize()
                        scheduleQualityUpgrade()
                        retryTask?.let{handler.removeCallbacks(it)}
                        retryTask=null
                    }
                }
                override fun onPlayerError(error:PlaybackException){
                    val httpStatus=(error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    android.util.Log.e("HomeTV", "Playback error for "+c.name+" HTTP="+httpStatus, error)
                    handleEngineFailure(index,screen)
                }
            })
            try{
                exo.setMediaItem(MediaItem.fromUri(c.url))
                exo.prepare()
                exo.play()
            }catch(e:Exception){showBanner("Cannot play ${c.name}: ${e.message}",true);handleEngineFailure(index,screen)}
        }
        updateClock()
        screen.requestFocus()
        showChannelInfo(index)
    }
    private fun reconnectCurrentChannel(c:Channel,index:Int,screen:FrameLayout){
        if(active!=index || videoScreen!==screen)return
        if(tryNextAlternative(index))return
        if(prefs.getBoolean("auto_reconnect",true) && retryCount<2){
            retryCount++
            showBanner("Reconnecting "+c.name+" (attempt "+retryCount+"/2)")
            val recoveryGeneration=playbackGeneration
            val task=Runnable{if(active==index && videoScreen===screen && playbackGeneration==recoveryGeneration)playIndex(index)}
            retryTask=task
            handler.postDelayed(task,2000L*retryCount)
        }else showBanner("Stream stopped. Try another source or channel.",true)
    }
    private fun startIjk(c:Channel,index:Int,screen:FrameLayout){
        try{
            val ijk=IjkMediaPlayer()
            ijkPlayer=ijk
            val surface=SurfaceView(this)
            ijkSurface=surface
            screen.addView(surface,0,FrameLayout.LayoutParams(-1,-1))
            ijk.setDisplay(surface.holder)
            ijk.setOnVideoSizeChangedListener{_,width,height,_,_->
                handler.post{if(ijkPlayer===ijk){ijkVideoWidth=width;ijkVideoHeight=height;applyVideoSize()}}
            }
            surface.post{if(ijkPlayer===ijk)applyVideoSize()}
            ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER,"mediacodec",1L)
            ijk.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT,"timeout",10000000L)
            var started=false
            var failed=false
            val watchdog=Runnable{
                if(!started && !failed && ijkPlayer===ijk && active==index && videoScreen===screen){
                    failed=true
                    handleEngineFailure(index,screen)
                }
            }
            ijk.setOnPreparedListener{
                handler.post{
                    if(ijkPlayer===ijk && active==index && videoScreen===screen){
                        started=true
                        handler.removeCallbacks(watchdog)
                        try{ijk.start();rememberWorking(c);autoBufferHealthy()}
                        catch(e:Exception){failed=true;android.util.Log.w("HomeTV","IJK start failed",e);handleEngineFailure(index,screen)}
                    }
                }
            }
            ijk.setOnErrorListener{_,_,_->
                handler.post{
                    if(ijkPlayer===ijk && active==index && videoScreen===screen && !failed){
                        failed=true
                        handler.removeCallbacks(watchdog)
                        handleEngineFailure(index,screen)
                    }
                }
                true
            }
            ijk.setOnCompletionListener{
                handler.post{
                    if(ijkPlayer===ijk && active==index && videoScreen===screen && !failed){
                        failed=true
                        handleEngineFailure(index,screen)
                    }
                }
            }
            ijk.setDataSource(this,Uri.parse(c.url),c.headers)
            ijk.prepareAsync()
            handler.postDelayed(watchdog,15000L)
        }catch(e:Exception){
            android.util.Log.w("HomeTV","IJKPlayer startup failed",e)
            handleEngineFailure(index,screen)
        }
    }
    private fun startVlc(c:Channel,index:Int,screen:FrameLayout){
        try{
            val cache=bufferValues()[0].coerceAtLeast(300)
            val options=arrayListOf("--network-caching="+cache,"--live-caching="+cache,"--http-reconnect","--no-video-title-show")
            val core=LibVLC(this,options)
            val vlc=VlcPlayer(core)
            vlcCore=core
            vlcPlayer=vlc
            val surface=SurfaceView(this)
            screen.addView(surface,0,FrameLayout.LayoutParams(-1,-1))
            vlc.vlcVout.setVideoView(surface)
            vlc.vlcVout.attachViews()
            surface.post{
                if(vlcPlayer===vlc){
                    val w=screen.width.takeIf{it>0} ?: resources.displayMetrics.widthPixels
                    val h=screen.height.takeIf{it>0} ?: resources.displayMetrics.heightPixels
                    vlc.vlcVout.setWindowSize(w,h)
                    applyVideoSize()
                }
            }
            var started=false
            var failed=false
            var lastHealthy=android.os.SystemClock.elapsedRealtime()
            val stallCheck=object:Runnable{
                override fun run(){
                    if(vlcPlayer!==vlc || active!=index || videoScreen!==screen)return
                    if(started && !failed && prefs.getBoolean("auto_reconnect",true) &&
                        android.os.SystemClock.elapsedRealtime()-lastHealthy>30000L){
                        failed=true
                        autoBufferStalled()
                        android.util.Log.w("HomeTV","LibVLC stalled for "+c.name)
                        handleEngineFailure(index,screen)
                        return
                    }
                    handler.postDelayed(this,5000)
                }
            }
            handler.postDelayed(stallCheck,5000)
            val watchdog=Runnable{
                if(!started && !failed && active==index && vlcPlayer===vlc){
                    failed=true
                    android.util.Log.w("HomeTV","LibVLC startup timeout for "+c.name)
                    showBanner(c.name+" did not start. Trying another stream.",true)
                    handleEngineFailure(index,screen)
                }
            }
            vlc.setEventListener{event->
                // LibVLC callbacks may arrive on native threads; process them
                // on the Activity main handler and ignore obsolete players.
                val eventType=event.type
                handler.post{
                    if(vlcPlayer!==vlc || active!=index || videoScreen!==screen)return@post
                    when(eventType){
                    VlcPlayer.Event.Playing->{
                        started=true
                        handler.postDelayed({
                            if(vlcPlayer===vlc && active==index && !failed &&
                                android.os.SystemClock.elapsedRealtime()-lastHealthy<12000L)
                                recoveryAttempts.remove(c.url)
                        },60000L)
                        rememberWorking(c)
                        autoBufferHealthy()
                        lastHealthy=android.os.SystemClock.elapsedRealtime()
                        handler.removeCallbacks(watchdog)
                    }
                    VlcPlayer.Event.TimeChanged, VlcPlayer.Event.PositionChanged,
                    VlcPlayer.Event.Buffering->{
                        // Buffering is not a playback failure. Give LibVLC time
                        // to recover without changing engines or tearing it down.
                        lastHealthy=android.os.SystemClock.elapsedRealtime()
                    }
                    VlcPlayer.Event.Stopped, VlcPlayer.Event.EndReached->handler.post{
                        if(active==index && vlcPlayer===vlc && !failed){
                            failed=true
                            handleEngineFailure(index,screen)
                        }
                    }
                    VlcPlayer.Event.EncounteredError->handler.post{
                        if(active==index && vlcPlayer===vlc && !failed){
                            failed=true
                            handler.removeCallbacks(watchdog)
                            handleEngineFailure(index,screen)
                        }
                    }
                    }
                }
            }
            handler.postDelayed(watchdog,6000L)
            val media=VlcMedia(core,Uri.parse(c.url))
            c.headers["User-Agent"]?.let{media.addOption(":http-user-agent="+it)}
            c.headers["Referer"]?.let{media.addOption(":http-referrer="+it)}
            media.addOption(":network-caching="+cache)
            vlc.media=media
            media.release()
            vlc.play()
            handler.postDelayed({if(vlcPlayer===vlc)applyVideoSize()},1000)
        }catch(e:Exception){
            android.util.Log.e("HomeTV","LibVLC startup failed",e)
            showBanner("LibVLC error: "+(e.message?:"unknown"),true)
            handleEngineFailure(index,screen)
        }
    }
    // Choose the nearest eligible channel strictly in the requested direction.
    // Compare playlist positions, not positions in the filtered surfing list.
    private fun nextSurfingIndex(current:Int,delta:Int,available:List<Int>):Int{
        if(available.isEmpty())return -1
        // Use the cached playlist positions rather than repeatedly scanning every
        // channel for every remote key press (which could freeze large playlists).
        ensureChannelIndex()
        val currentPosition=channels.getOrNull(current)?.let{channelPosition[channelKey(it)]} ?: 0
        var forwardIndex=-1
        var forwardPosition=Int.MAX_VALUE
        var backwardIndex=-1
        var backwardPosition=Int.MIN_VALUE
        var firstIndex=-1
        var firstPosition=Int.MAX_VALUE
        var lastIndex=-1
        var lastPosition=Int.MIN_VALUE
        for(candidate in available){
            val channel=channels.getOrNull(candidate) ?:continue
            val position=channelPosition[channelKey(channel)] ?:continue
            if(position<firstPosition){firstPosition=position;firstIndex=candidate}
            if(position>lastPosition){lastPosition=position;lastIndex=candidate}
            if(position>currentPosition && position<forwardPosition){forwardPosition=position;forwardIndex=candidate}
            if(position<currentPosition && position>backwardPosition){backwardPosition=position;backwardIndex=candidate}
        }
        return if(delta>=0)if(forwardIndex>=0)forwardIndex else firstIndex
            else if(backwardIndex>=0)backwardIndex else lastIndex
    }
    private fun switchChannel(delta:Int){
        surfingDirection=if(delta>=0)1 else -1
        val available=surfingIndices()
        if(available.isEmpty())return
        val next=nextSurfingIndex(active,delta,available)
        playIndex(preferredChannel(next))
    }
    private fun resumeLast(){
        if(channels.isEmpty()){showHome();return}
        val url=prefs.getString("last_url",null)
        val index=channels.indexOfFirst{it.url==url}
        val available=surfingIndices()
        if(index>=0 && !isLocked(channels[index]))playIndex(index)
        else if(available.isNotEmpty())playIndex(preferredChannel(available.first()))
        else allChannels()
    }
    private fun liveSettings(){
        if(active<0){settings();return}
        val items=arrayOf(
            "Playback engine: "+(prefs.getString("engine","Media3")?:"Media3"),
            "Buffering: "+bufferProfile(),
            "Universal picture quality: "+(prefs.getString("quality_global","Auto")?:"Auto"),
            "Universal screen size: "+(prefs.getString("screen_mode","16:9")?:"Fit"),
            "Auto-reconnect: "+if(prefs.getBoolean("auto_reconnect",true))"ON" else "OFF",
            "Resume last channel: "+if(prefs.getBoolean("resume",true))"ON" else "OFF",
            "Date & time overlay",
            "Manage playlists (leave playback)",
            "Home screen (stop playback)",
            "Back to TV"
        )
        val liveDialog=AlertDialog.Builder(this).setTitle("HomeTV Settings — Live TV")
            .setItems(items){_,choice->
                when(choice){
                    0->{
                        val choices=arrayOf("Auto","Media3","LibVLC")
                        AlertDialog.Builder(this).setTitle("Default playback engine")
                            .setSingleChoiceItems(choices,choices.indexOf(prefs.getString("engine","Media3")).coerceAtLeast(0)){dialog,i->
                                prefs.edit().putString("engine",choices[i]).apply()
                                dialog.dismiss()
                                showBanner("Playback engine saved for next channel")
                            }.setNegativeButton("Cancel",null).show()
                    }
                    1->chooseBufferProfile()
                    2->selectQuality(null)
                    3->selectScreenMode(null)
                    4->{
                        prefs.edit().putBoolean("auto_reconnect",!prefs.getBoolean("auto_reconnect",true)).apply()
                        showBanner("Auto-reconnect setting saved")
                    }
                    5->{
                        prefs.edit().putBoolean("resume",!prefs.getBoolean("resume",true)).apply()
                        showBanner("Resume setting saved")
                    }
                    6->clockSettings()
                    7->AlertDialog.Builder(this).setTitle("Leave live TV?")
                        .setMessage("Managing playlists will stop the current channel.")
                        .setPositiveButton("Continue"){_,_->managePlaylists()}
                        .setNegativeButton("Stay on TV",null).show()
                    8->showHome()
                }
            }.setNegativeButton("Close",null).create()
        showSideDialog(liveDialog)
    }
    private fun exportSettings(){
        val excluded=setOf("license_key","license_device","license_expiry","pin","last_url")
        val data=JSONObject()
        prefs.all.forEach{(key,value)->
            if(key in excluded || key.startsWith("license_") ||
                key.contains("password",ignoreCase=true) || key.contains("secret",ignoreCase=true) ||
                key.contains("token",ignoreCase=true) || key.contains("credential",ignoreCase=true))return@forEach
            when(value){
                is String->data.put(key,JSONObject().put("type","string").put("value",value))
                is Boolean->data.put(key,JSONObject().put("type","boolean").put("value",value))
                is Int->data.put(key,JSONObject().put("type","int").put("value",value))
                is Long->data.put(key,JSONObject().put("type","long").put("value",value))
                is Float->data.put(key,JSONObject().put("type","float").put("value",value.toDouble()))
                is Set<*>->data.put(key,JSONObject().put("type","strings").put("value",JSONArray(value.filterIsInstance<String>())))
            }
        }
        val payload=JSONObject().put("format","HomeTV-settings-v1").put("settings",data).toString(2)
        val intent=Intent(Intent.ACTION_SEND).apply{
            type="text/plain";putExtra(Intent.EXTRA_SUBJECT,"HomeTV settings backup")
            putExtra(Intent.EXTRA_TEXT,payload)
        }
        startActivity(Intent.createChooser(intent,"Export HomeTV settings"))
    }
    private fun importSettings(){
        val input=EditText(this).apply{
            hint="Paste HomeTV-settings-v1 JSON backup"
            minLines=6;maxLines=12;setSingleLine(false)
        }
        AlertDialog.Builder(this).setTitle("Restore settings (not license or PIN)")
            .setView(input).setPositiveButton("Restore"){_,_->
                try{
                    val backup=JSONObject(input.text.toString())
                    require(backup.optString("format")=="HomeTV-settings-v1")
                    val values=backup.getJSONObject("settings")
                    val edit=prefs.edit()
                    val iterator=values.keys()
                    var count=0
                    while(iterator.hasNext()){
                        val key=iterator.next()
                        if(key=="pin" || key=="last_url" || key.startsWith("license_") ||
                            key.contains("password",ignoreCase=true) || key.contains("secret",ignoreCase=true) ||
                            key.contains("token",ignoreCase=true) || key.contains("credential",ignoreCase=true))continue
                        val item=values.getJSONObject(key)
                        when(item.getString("type")){
                            "string"->edit.putString(key,item.getString("value"))
                            "boolean"->edit.putBoolean(key,item.getBoolean("value"))
                            "int"->edit.putInt(key,item.getInt("value"))
                            "long"->edit.putLong(key,item.getLong("value"))
                            "float"->edit.putFloat(key,item.getDouble("value").toFloat())
                            "strings"->{
                                val arr=item.getJSONArray("value")
                                edit.putStringSet(key,(0 until arr.length()).map{arr.getString(it)}.toSet())
                            }
                            else->continue
                        }
                        count++
                    }
                    edit.apply()
                    Toast.makeText(this,"Restored "+count+" settings. Restart HomeTV to apply all changes.",Toast.LENGTH_LONG).show()
                }catch(e:Exception){
                    Toast.makeText(this,"Invalid backup: "+e.message,Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("Cancel",null).show()
    }
    private fun showPlaybackDiagnostics(){
        val current=channels.getOrNull(active)
        val lines=listOf(
            "Channel: "+(current?.name ?: "None"),
            "Source: "+(current?.source ?: "None"),
            "Playback engine: "+(current?.let{engineFor(it)} ?: "Not playing"),
            "Buffering profile: "+bufferProfile(),
            "Auto reconnect: "+prefs.getBoolean("auto_reconnect",true),
            "Background checked URLs: "+probeReachable.size,
            "Background unavailable URLs: "+probeReachable.values.count{!it},
            "Retry attempts: "+retryCount,
            "Channel alternatives attempted: "+alternativeAttempted.size
        )
        AlertDialog.Builder(this).setTitle("Playback diagnostics")
            .setMessage(lines.joinToString("\\n"))
            .setPositiveButton("Close",null).show()
    }
    private fun settingsToggle(title:String,key:String,defaultValue:Boolean,onChanged:(()->Unit)?=null):Button{
        val enabled=prefs.getBoolean(key,defaultValue)
        return button(title+"    ["+(if(enabled)"ON" else "OFF")+"]") {
            val current=prefs.getBoolean(key,defaultValue)
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(arrayOf("ON","OFF"),if(current)0 else 1){dialog,which->
                    val chosen=which==0
                    if(chosen!=current){
                        prefs.edit().putBoolean(key,chosen).apply()
                        onChanged?.invoke()
                    }
                    dialog.dismiss()
                    settings()
                }.setNegativeButton("Cancel",null).show()
        }
    }
    private var settingsFocusedIndex=-1
    private var settingsScrollY=0
    private var settingsScreenVisible=false
    private fun settings(){
        // Capture the focused setting before rebuilding the menu after a toggle.
        if(settingsScreenVisible){
            val focused=root.findFocus()
            val child=root.indexOfChild(focused)
            if(child>=0)settingsFocusedIndex=child
            settingsScrollY=(root.parent as? android.widget.ScrollView)?.scrollY ?: settingsScrollY
        }else{
            settingsFocusedIndex=-1
            settingsScrollY=0
        }
        reset("HomeTV Settings")
        settingsScreenVisible=true
        root.addView(button("Surrender License / Change TV"){surrenderLicense()})
        root.addView(button("Manage playlists"){managePlaylists()})
        root.addView(button("Export settings backup"){exportSettings()})
        root.addView(button("Restore settings backup"){importSettings()})
        root.addView(button("Playback diagnostics"){showPlaybackDiagnostics()})
        root.addView(button("Test network download speed"){runNetworkDiagnostics()})
        root.addView(button("Sleep timer"){sleepTimer()})
        root.addView(button("Favorites"){favorites()})
        root.addView(button("Hidden channels"){hiddenChannels()})
        root.addView(button("EPG / XMLTV guide"){configureEpg()})
        root.addView(button("EPG time correction"){configureEpgOffset()})
        root.addView(button("Viewer profile: "+(if(viewerId()=="default")"Default" else viewerId())){manageViewerProfiles()})
        root.addView(button("Information transparency: "+prefs.getInt("information_opacity",65)+"%"){chooseInformationTransparency()})
        root.addView(settingsToggle("Show EPG in channel info","show_channel_epg",true))
        root.addView(button("Playback engine: "+(prefs.getString("engine","Media3")?:"Media3")){
            val choices=arrayOf("Auto","Media3","LibVLC")
            AlertDialog.Builder(this).setTitle("Playback engine").setSingleChoiceItems(choices,choices.indexOf(prefs.getString("engine","Media3"))){dialog,i->
                prefs.edit().putString("engine",choices[i]).apply()
                dialog.dismiss();settings()
            }.setNegativeButton("Cancel",null).show()
        })
        root.addView(settingsToggle("Live video on Up / Down preview","live_channel_preview",false))
        root.addView(button("Buffering: "+bufferProfile()){chooseBufferProfile()})
        root.addView(label("Ultra Fast = quickest switching; Balanced = recommended; Stable / Maximum Buffer = fewer pauses on uneven networks. Changes apply to the next channel."))
        root.addView(settingsToggle("Background URL checking","background_url_checks",false){startBackgroundChannelChecks()})
        root.addView(label("YouTube playback: WebView watch page (restored compatibility mode)"))
        root.addView(settingsToggle("Auto-skip non-working channels","auto_skip_nonworking",true))
        root.addView(label("ON: after all URLs and both players fail, automatically move to the next available channel. OFF: stay on the failed channel. Background URL checking stays separate."))
        root.addView(settingsToggle("Auto-reconnect streams","auto_reconnect",true))
        root.addView(button("Manage locked channels / groups"){manageLockedChannels()})
        root.addView(button("Channels skipped by Up/Down and CH+/−"){manageSkippedChannels()})
        root.addView(button("Set / change lock password"){
            val field=EditText(this).apply{
                hint="Any letters or digits (no minimum length)"
                inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            val save:()->Unit={
                val value=field.text.toString()
                if(value.isNotEmpty()){
                    prefs.edit().putString("pin",hash(value)).apply()
                    Toast.makeText(this,"Lock password saved",Toast.LENGTH_SHORT).show()
                }else Toast.makeText(this,"Password cannot be empty",Toast.LENGTH_SHORT).show()
            }
            val prompt={AlertDialog.Builder(this).setTitle("New lock password").setView(field)
                .setPositiveButton("Save"){_,_->save()}.setNegativeButton("Cancel",null).show()}
            if(prefs.contains("pin"))requirePin{prompt()} else prompt()
        })
        root.addView(settingsToggle("Resume last channel","resume",true))
        root.addView(button("Open on launch: "+(prefs.getString("launch_screen","Last channel")?:"Last channel")){
            val choices=arrayOf("Home","Last channel","Channels")
            AlertDialog.Builder(this).setTitle("When HomeTV opens").setSingleChoiceItems(choices,choices.indexOf(prefs.getString("launch_screen","Last channel"))){dialog,i->
                prefs.edit().putString("launch_screen",choices[i]).apply();dialog.dismiss();settings()
            }.setNegativeButton("Cancel",null).show()
        })
        root.addView(settingsToggle("Try launch at device boot","boot",false))
        root.addView(label("Auto-start at TV power-on is restricted on Android 10+ and depends on TV manufacturer settings. Enabling this does not guarantee launch."))
        root.addView(button("Universal picture quality: "+(prefs.getString("quality_global","Auto") ?: "Auto")){selectQuality()})
        root.addView(label("Picture quality controls available stream resolutions in Media3. LibVLC and fixed-quality streams may ignore this setting."))
        root.addView(button("Universal screen size: "+(prefs.getString("screen_mode","16:9") ?: "Fit")){selectScreenMode()})
        root.addView(button("Date & time overlay"){clockSettings()})
        root.addView(label("Remote: Up previews next channel; Down previews previous channel; OK plays selected channel; Left opens channels; Right/Menu opens playback settings."))
        root.addView(button("Home"){showHome()})
        val focusIndex=settingsFocusedIndex
        val savedScroll=settingsScrollY
        root.post{
            if(settingsScreenVisible && focusIndex in 1 until root.childCount){
                root.getChildAt(focusIndex).requestFocus()
                (root.parent as? android.widget.ScrollView)?.scrollTo(0,savedScroll)
            }
        }
    }
    private var volumeOverlay:View?=null
    private var volumeOverlayTask:Runnable?=null
    private fun showHomeTvVolume(){
        val screen=videoScreen ?:return
        val audio=getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val max=audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        volumeOverlayTask?.let{handler.removeCallbacks(it)}
        volumeOverlay?.let{(it.parent as? android.view.ViewGroup)?.removeView(it)}
        val density=resources.displayMetrics.density
        val height=(220*density).toInt()
        val width=(12*density).toInt()
        val filled=(height*current/max).coerceIn(0,height)
        val panel=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER
            setPadding((15*density).toInt(),(16*density).toInt(),(15*density).toInt(),(16*density).toInt())
            setBackgroundColor(0xE515283C.toInt())
            isClickable=false
        }
        panel.addView(TextView(this).apply{
            text="VOL"
            textSize=12f
            gravity=Gravity.CENTER
            setTextColor(0xFFFFB900.toInt())
        })
        val track=FrameLayout(this).apply{setBackgroundColor(0xFF344B63.toInt())}
        val fill=View(this).apply{setBackgroundColor(0xFFFFB900.toInt())}
        track.addView(fill,FrameLayout.LayoutParams(width,filled,Gravity.BOTTOM))
        panel.addView(track,LinearLayout.LayoutParams(width,height).apply{topMargin=(10*density).toInt();bottomMargin=(10*density).toInt()})
        panel.addView(TextView(this).apply{
            text="${current*100/max}%"
            textSize=14f
            gravity=Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
        })
        screen.addView(panel,FrameLayout.LayoutParams(-2,-2,Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply{rightMargin=(20*density).toInt()})
        volumeOverlay=panel
        val task=Runnable{if(volumeOverlay===panel){(panel.parent as? android.view.ViewGroup)?.removeView(panel);volumeOverlay=null}}
        volumeOverlayTask=task
        handler.postDelayed(task,2500L)
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean{
        // Give a visible AlertDialog its own remote OK and directional navigation.
        // Avoid processing dialog keys as channel commands in the Activity.
        if(!window.decorView.hasWindowFocus())return super.dispatchKeyEvent(event)
        if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount>0 &&
            (event.keyCode==KeyEvent.KEYCODE_DPAD_CENTER ||
             event.keyCode==KeyEvent.KEYCODE_ENTER ||
             event.keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER))return true
        if(event.action==KeyEvent.ACTION_DOWN && active>=0 &&
            (event.keyCode==KeyEvent.KEYCODE_VOLUME_UP || event.keyCode==KeyEvent.KEYCODE_VOLUME_DOWN)){
            val audio=getSystemService(AUDIO_SERVICE) as android.media.AudioManager
            val direction=if(event.keyCode==KeyEvent.KEYCODE_VOLUME_UP)android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER
            audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,direction,0)
            // A TV remote volume key is a user action; retry unmuting
            // YouTube without affecting Media3 or LibVLC playback.
            if(event.keyCode==KeyEvent.KEYCODE_VOLUME_UP){
                val current=channels.getOrNull(active)
                val host=try{current?.url?.let{Uri.parse(it).host?.lowercase(java.util.Locale.ROOT)}}catch(_:Exception){null}
                if(host!=null && (host=="youtu.be" || host=="youtube.com" ||
                    host.endsWith(".youtube.com") || host=="youtube-nocookie.com"))
                    web?.let{unmuteYoutube(it,false)}
            }
            showHomeTvVolume()
            return true
        }
        if(active>=0 && event.action==KeyEvent.ACTION_DOWN){
            val digit=when(event.keyCode){
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9->event.keyCode-KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9->event.keyCode-KeyEvent.KEYCODE_NUMPAD_0
                else->-1
            }
            if(digit>=0){enterChannelDigit(digit);return true}
            if(channelDigits.isNotEmpty() && (event.keyCode==KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode==KeyEvent.KEYCODE_ENTER || event.keyCode==KeyEvent.KEYCODE_NUMPAD_ENTER)){
                tuneTypedChannel();return true
            }
            // Dispatch before PlayerView/SurfaceView can consume remote DPAD events.
            // Let an open channel drawer handle its own list navigation.
            val drawerOpen=videoScreen?.findViewWithTag<View>("channel_drawer")!=null || videoScreen?.findViewWithTag<View>("settings_drawer")!=null || videoScreen?.findViewWithTag<View>("left_navigation")!=null
            if(!drawerOpen){
                val media=channels.getOrNull(active)?.mediaType
                if(media in setOf("movie","series","music")){
                    if(isActiveYoutubeMedia()){
                        when(event.keyCode){
                            KeyEvent.KEYCODE_DPAD_LEFT->{youtubeRemoteAction("back");return true}
                            KeyEvent.KEYCODE_DPAD_RIGHT->{youtubeRemoteAction("forward");return true}
                            KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE->{
                                youtubeRemoteAction("toggle");return true
                            }
                            KeyEvent.KEYCODE_BACK->{onBackPressed();return true}
                        }
                    }
                    when(event.keyCode){
                        KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER->{mediaPlaybackControls();return true}
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE->{
                            player?.let{if(it.isPlaying)it.pause() else it.play()}
                            vlcPlayer?.let{if(it.isPlaying)it.pause() else it.play()}
                            return true
                        }
                        KeyEvent.KEYCODE_BACK->{onBackPressed();return true}
                    }
                }
                when(event.keyCode){
                    KeyEvent.KEYCODE_LAST_CHANNEL->{returnToPreviousChannel();return true}
                    KeyEvent.KEYCODE_DPAD_UP->{previewChannel(1);return true}
                    KeyEvent.KEYCODE_DPAD_DOWN->{previewChannel(-1);return true}
                    KeyEvent.KEYCODE_CHANNEL_DOWN,KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    KeyEvent.KEYCODE_BUTTON_L1->{switchChannel(-1);return true}
                    KeyEvent.KEYCODE_CHANNEL_UP,KeyEvent.KEYCODE_MEDIA_NEXT,
                    KeyEvent.KEYCODE_BUTTON_R1->{switchChannel(1);return true}
                    KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER->{if(previewIndex>=0)confirmPreview() else if(active in channels.indices){if(banner!=null && prefs.getBoolean("show_channel_epg",true))openCurrentChannelEpg() else showChannelInfo(active)};return true}
                    KeyEvent.KEYCODE_DPAD_LEFT->{showOverlayList();return true}
                    KeyEvent.KEYCODE_DPAD_RIGHT->{showChannelOptions();return true}
                    KeyEvent.KEYCODE_MENU->{showPlaybackSettings();return true}
                    KeyEvent.KEYCODE_BACK->{if(previewIndex>=0){previewIndex=-1;closeVideoPreview();return true};onBackPressed();return true}
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onBackPressed(){
        if(!licenseReady){showHome();return}
        // Navigate back to the screen that opened Settings, never exit here.
        if(settingsScreenVisible){
            val previous=if(navigationHistory.isNotEmpty())navigationHistory.removeAt(navigationHistory.lastIndex) else ({showHome()})
            navigatingBack=true
            try{previous()}finally{navigatingBack=false}
            return
        }
        if(active>=0){
            val screen=videoScreen
            val drawer=screen?.findViewWithTag<View>("channel_drawer")
                ?:screen?.findViewWithTag<View>("settings_drawer")
                ?:screen?.findViewWithTag<View>("left_navigation")
            if(drawer!=null && screen!=null){
                screen.removeView(drawer)
                screen.requestFocus()
                return
            }
            confirmExit()
        }else{
            // Non-playback menus return to Home. Exit is offered only in playback.
            showHome()
        }
    }
    private fun stopPlayback(){
        saveCurrentMediaProgress()
        networkRecoveryTask?.let{handler.removeCallbacks(it)}
        networkRecoveryTask=null
        closeVideoPreview()
        playbackGeneration++
        startupWatchdog?.let{handler.removeCallbacks(it)}
        startupWatchdog=null
        previewIndex=-1
        youtubeUnmuteTask?.let{handler.removeCallbacks(it)}
        youtubeUnmuteTask=null
        youtubeProgressTask?.let{handler.removeCallbacks(it)}
        youtubeProgressTask=null
        channelDigitTask?.let{handler.removeCallbacks(it)}
        channelDigitTask=null
        channelDigits=""
        clearNumericOverlay()
        previewTask?.let{handler.removeCallbacks(it)}
        previewTask=null
        bannerTask?.let{handler.removeCallbacks(it)}
        clockTask?.let{handler.removeCallbacks(it)}
        clockTask=null
        retryTask?.let{handler.removeCallbacks(it)}
        retryTask=null
        qualityUpgradeTask?.let{handler.removeCallbacks(it)}
        qualityUpgradeTask=null
        bannerTask=null;banner=null;videoScreen=null;currentPlayerView=null
        ijkPlayer?.let{try{it.stop();it.release()}catch(_:Exception){}};ijkPlayer=null
        ijkSurface=null;ijkVideoWidth=0;ijkVideoHeight=0
        player?.release();player=null
        vlcPlayer?.let{v->
            try{v.stop();v.vlcVout.detachViews();v.release()}catch(_:Exception){}
        }
        vlcPlayer=null
        vlcCore?.release();vlcCore=null
        web?.onPause();web?.destroy();web=null
        active=-1
    }
    override fun onDestroy(){epgTuneTask?.let{handler.removeCallbacks(it)};epgTuneTask=null;sleepTimerTask?.let{handler.removeCallbacks(it)};sleepTimerTask=null;stopVlcProbe();backgroundProbeGeneration++;periodicProbeTask?.let{handler.removeCallbacks(it)};probePlayers.toList().forEach{it.release()};probePlayers.clear();stopPlayback();super.onDestroy()}
}
