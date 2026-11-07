package com.softppj.hometv

import android.app.*
import android.os.*
import android.content.*
import android.view.*
import android.widget.*
import android.webkit.*
import android.net.Uri
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
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.common.AudioAttributes
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.effect.HslAdjustment
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
    private val licenseApi="https://hometv-license-next.prasanthpjoy.workers.dev"
    private val licensedPlaylist="hometv://licensed-playlist"
    private var licenseReady=false
    private var licenseChecking=false
    private var startupHiddenScanScheduled=false
    private var hiddenChannelCheckRunning=false
    private var licenseOperationGeneration=0
    private var licenseNetworkManager:android.net.ConnectivityManager?=null
    private var licenseNetworkCallback:android.net.ConnectivityManager.NetworkCallback?=null
    private var safeStartupMode=false
    private val exportBackupRequest=7102
    private val exportDiagnosticsRequest=7103
    private val importBackupRequest=7104
    private var pendingTextExport:String?=null
    private class LicenseHttpException(val statusCode:Int,message:String):IllegalStateException(message)
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
            if(status !in 200..299)throw LicenseHttpException(status,result.optString("error","License server error: "+status))
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
            setImageResource(R.drawable.hometv_launcher)
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
    private fun signedLicenseCache(token:String)=SignedLicenseCache(prefs,installationId(),token)

    private fun registerLicenseNetworkCallback(){
        if(licenseNetworkCallback!=null)return
        val manager=getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?:return
        val callback=object:android.net.ConnectivityManager.NetworkCallback(){
            override fun onAvailable(network:android.net.Network){handler.post{onLicenseNetworkAvailable()}}
            override fun onCapabilitiesChanged(network:android.net.Network,capabilities:android.net.NetworkCapabilities){
                if(capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)){
                    handler.post{onLicenseNetworkAvailable()}
                }
            }
        }
        try{
            manager.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback
            )
            licenseNetworkManager=manager
            licenseNetworkCallback=callback
        }catch(e:Exception){HomeTvDiagnostics.w("HomeTV","Unable to register licence connectivity callback",e)}
    }
    private fun unregisterLicenseNetworkCallback(){
        val manager=licenseNetworkManager
        val callback=licenseNetworkCallback
        if(manager!=null && callback!=null)try{manager.unregisterNetworkCallback(callback)}catch(_:Exception){}
        licenseNetworkManager=null
        licenseNetworkCallback=null
    }
    private fun onLicenseNetworkAvailable(){
        if(!hasNetworkConnection() || isFinishing || isDestroyed || licenseChecking)return
        val token=prefs.getString("license_token",null)?.takeIf{it.isNotBlank()} ?:return
        if(!licenseReady){beginLicensedSession();return}
        val cache=signedLicenseCache(token)
        val cached=cache.status()
        if(!cached.allowed){beginLicensedSession();return}
        if(!cached.needsRevalidation)return
        licenseChecking=true
        val operation=++licenseOperationGeneration
        thread(name="HomeTV-license-revalidation"){
            var rejected=false
            val response=try{licenseRequest("/validate",licensePayload())}
            catch(e:LicenseHttpException){rejected=e.statusCode in 400..499 && e.statusCode!=429;null}
            catch(_:Exception){null}
            val saved=response?.let{cache.saveVerified(it)} ?:false
            runOnUiThread{
                if(operation!=licenseOperationGeneration || isFinishing || isDestroyed)return@runOnUiThread
                licenseChecking=false
                if(response!=null && saved){
                    saveLicenseExpiry(response)
                    prefs.edit().putBoolean("license_last_verified",true)
                        .putBoolean("license_validation_blocked",false)
                        .putLong("license_verified_at",System.currentTimeMillis())
                        .putString("license_verified_device",installationId())
                        .putString("license_verified_token",token).apply()
                    HomeTvDiagnostics.i("HomeTV","Signed licence revalidated after network availability")
                }else if(response!=null || rejected){
                    cache.clear()
                    licenseReady=false
                    showLicenseScreen(if(rejected)
                        "The license server rejected this activation. Please reactivate or contact support."
                    else "The license server returned an invalid signed lease. Please retry activation.")
                }else{
                    HomeTvDiagnostics.w("HomeTV","Licence revalidation deferred after a transient network failure")
                }
            }
        }
    }
    private fun beginLicensedSession(){
        if(licenseChecking)return
        val token=prefs.getString("license_token",null)
        if(token.isNullOrBlank()){
            licenseReady=false
            showLicenseScreen()
            return
        }
        // Only a cryptographically verified, device- and token-bound lease may
        // authorize offline startup. The signed lease itself enforces expiry,
        // clock rollback detection and the seven-day offline ceiling.
        val cache=signedLicenseCache(token)
        val cachedStatus=cache.status()
        val cachedAllowed=cachedStatus.allowed
        licenseChecking=true
        val operation=++licenseOperationGeneration
        if(cachedAllowed){
            licenseReady=true
            startLicensedApp()
            // Match the V6 licensing requirement: validate locally on every
            // launch, but contact the Worker only after the 24-hour recheck age.
            if(!cachedStatus.needsRevalidation){
                licenseChecking=false
                return
            }
        }else{
            licenseReady=false
            showLicenseCheckingScreen()
        }
        thread{
            var serverRejected=false
            val response=try{
                licenseRequest("/validate",licensePayload())
            }catch(e:LicenseHttpException){
                serverRejected=e.statusCode in 400..499 && e.statusCode!=429
                null
            }catch(e:Exception){null}
            val saved=response?.let{cache.saveVerified(it)} ?: false
            runOnUiThread{
                if(operation!=licenseOperationGeneration || isFinishing || isDestroyed)return@runOnUiThread
                licenseChecking=false
                if(response!=null && saved){
                    saveLicenseExpiry(response)
                    prefs.edit().putBoolean("license_last_verified",true)
                        .putBoolean("license_validation_blocked",false)
                        .putLong("license_verified_at",System.currentTimeMillis())
                        .putString("license_verified_device",installationId())
                        .putString("license_verified_token",token).apply()
                    if(!licenseReady){licenseReady=true;startLicensedApp()}
                }else if(response==null && cachedAllowed && !serverRejected){
                    // A transient network error does not invalidate a valid signed
                    // lease. It cannot extend the signed seven-day offline window.
                    android.util.Log.w("HomeTV","Background signed-lease refresh unavailable")
                }else{
                    // A server response that cannot be verified is not a network
                    // outage: fail closed and discard the cached entitlement.
                    cache.clear()
                    prefs.edit().putBoolean("license_validation_blocked",true)
                        .putBoolean("license_last_verified",false).commit()
                    licenseReady=false
                    showLicenseScreen(when {
                        serverRejected -> "The license server rejected this activation. Please reactivate or contact support."
                        response!=null -> "The license server returned an invalid signed lease. Please retry activation."
                        else -> "License verification failed. Connect to the internet and retry."
                    })
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
                HomeTvDiagnostics.w("HomeTV","Cached playlist parse failed",e)
                mutableListOf<Channel>()
            }
            runOnUiThread{
                if(operation!=licenseOperationGeneration || !licenseReady || isFinishing || isDestroyed)return@runOnUiThread
                channels=cached
                checkEpgTune()
                // Startup uses saved playlist data immediately. Never block opening
                // the app on network refreshes of every configured playlist.
                if(channels.isNotEmpty()){
                    openStartupDestination()
                    // Automatic playlist checks are optional and never delay first picture.
                    if(!safeStartupMode && prefs.getBoolean("auto_playlist_check",true))
                        refresh({},activeOnly=true)
                } else {
                    // A changed playlist URL has a new cache key. Fetch it on first
                    // launch instead of silently opening an empty Home screen.
                    refresh({
                        if(channels.isNotEmpty())openStartupDestination()
                        else showHome()
                    })
                }
            }
        }
    }
    private fun openStartupDestination(){
        if(safeStartupMode){
            Toast.makeText(this,"Safe startup enabled after repeated crashes. Automatic resume and background checks are paused.",Toast.LENGTH_LONG).show()
            showHome()
            return
        }
        when(prefs.getString("launch_screen","Last channel") ?: "Last channel"){
            "Home"->showHome()
            "Channels"->allChannels()
            else->if(prefs.getBoolean("resume",true))resumeLast() else showHome()
        }
        scheduleStartupHiddenChannelScan()
    }
    // Hidden-channel checks are deliberately delayed until after the cached
    // playlist has opened, so startup and first-picture time are unaffected.
    // The automatic pass is non-modal; users can review results from the
    // existing manual "Check hidden channels" action.
    private fun scheduleStartupHiddenChannelScan(){
        if(startupHiddenScanScheduled || safeStartupMode || !licenseReady)return
        startupHiddenScanScheduled=true
        handler.postDelayed({
            if(isFinishing || isDestroyed)return@postDelayed
            if(!appInForeground || channels.isEmpty() ||
                !prefs.getBoolean("auto_hidden_channel_check",true)){
                startupHiddenScanScheduled=false
                return@postDelayed
            }
            if(licenseReady && !safeStartupMode){
                checkHiddenChannelsNow(showResults=false)
            }else startupHiddenScanScheduled=false
        },30_000L)
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
                    // Match V6: activation is followed by the standard /validate check.
                    val verified=licenseRequest("/validate",JSONObject()
                        .put("device",installationId()).put("token",token))
                    if(!signedLicenseCache(token).saveVerified(verified))
                        throw IllegalStateException("The license server returned an invalid signed lease")
                    activationResult=verified
                    null
                }catch(e:Exception){e.message?:"Activation failed"}
                runOnUiThread{
                    if(operation!=licenseOperationGeneration || isFinishing || isDestroyed)return@runOnUiThread
                    if(token!=null && error==null){
                        prefs.edit().putString("license_token",token)
                            .putBoolean("license_last_verified",true)
                            .putBoolean("license_validation_blocked",false).apply()
                        activationResult?.let{saveLicenseExpiry(it)}
                        licenseReady=true
                        startLicensedApp()
                    }else{
                        licenseReady=false
                        showLicenseScreen(error)
                    }
                }
            }
        }
        root.addView(activate)
        if(!hasNetworkConnection()){
            root.addView(label("NO INTERNET CONNECTION"))
            root.addView(label("Connect this TV to the internet. A valid cached licence can continue offline for up to seven days; a new activation or expired cache needs internet access."))
        }else{
            root.addView(label("An internet connection is required for activation and license checks."))
        }
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
                            prefs.getString("license_token",null)?.let{signedLicenseCache(it).clear()}
                            prefs.edit().remove("license_token").remove("license_expiry")
                                .putBoolean("license_last_verified",false).putBoolean("license_validation_blocked",true).apply()
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
    private var activeChannelGainProcessor:ChannelGainAudioProcessor?=null
    private var vlcCore:LibVLC?=null
    private var vlcPlayer:VlcPlayer?=null
    private var vlcAttempted=false
    private val engineAttempts=mutableMapOf<String,MutableSet<String>>()
    private val recoveryAttempts=mutableMapOf<String,Int>()
    private val unavailableChannels=mutableSetOf<String>()
    private val playedThisSession=mutableSetOf<String>()
    private val failedUrlsThisSession=mutableSetOf<String>()
    private var fallbackEngineOverride:String?=null
    private var pendingPlaybackDrawerChannelKey:String?=null
    private var engineChannelUrl:String?=null
    private var web:WebView?=null
    // A YouTube page can finish more than once during redirects/SPA navigation.
    // Initialise its startup helpers only once for each channel playback generation.
    private var youtubePageInitializedGeneration=-1
    // Native YouTube is attempted once per selected URL; WebView remains the fallback.
    private var youtubeNativeAttemptedUrl:String?=null
    private var youtubeNativeStreamUrl:String?=null
    private var youtubeNativeGeneration=0
    private var youtubeNativeTransition=false
    private var youtubeResolveTimeout:Runnable?=null
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
    private var continuousChannelPosition=emptyMap<String,Int>()
    private var channelSources=emptySet<String>()
    private fun channelKey(c:Channel)=HomeTvPolicies.channelKey(c)
    private fun ensureChannelIndex(){
        if(channelIndexOwner===channels && channelIndexSize==channels.size)return
        val snapshot=HomeTvPolicies.buildChannelIndex(channels)
        channelByKey=snapshot.indicesByKey
        channelRepresentatives=snapshot.representatives
        liveChannelRepresentatives=snapshot.liveRepresentatives
        channelPosition=snapshot.perPlaylistNumbers
        continuousChannelPosition=snapshot.continuousNumbers
        channelSources=channels.asSequence().map{it.source}.toSet()
        channelIndexOwner=channels
        channelIndexSize=channels.size
        invalidateSurfingCache()
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
            if(url in failedUrlsThisSession && url!=manual && url!=saved)continue
            if(firstUsable==null)firstUsable=candidate
            if(manual!=null && url==manual)return candidate
            if(savedMatch==null && saved!=null && url==saved)savedMatch=candidate
            if(reachableMatch==null && probeReachable[url]==true)reachableMatch=candidate
            if(vlcMatch==null && vlcProbeReachable[url]==true)vlcMatch=candidate
        }
        return savedMatch ?:reachableMatch ?:vlcMatch ?:firstUsable ?:group.first()
    }
    private fun rememberWorking(c:Channel){
        // Recovery succeeded: any delayed offer is now obsolete.
        recoveryPending.remove(channelKey(c))
        dismissAlternativePrompt(channelKey(c))
        recoveryAttempts.remove(c.url)
        playedThisSession.add(channelKey(c))
        recoveryPrompted.remove(channelKey(c))
        failedUrlsThisSession.remove(c.url)
        val recoveredUnavailable=unavailableChannels.remove(channelKey(c))
        val recoveredTemporary=temporaryFailureHidden.remove(channelKey(c))
        if(recoveredUnavailable || recoveredTemporary)invalidateSurfingCache()
        // Auto-unhide is deliberately tied to a real foreground Playing event,
        // never to an HTTP 200 response or a timeout-based reachability probe.
        if(HomeTvPolicies.shouldAutoUnhide(
                prefs.getBoolean("auto_unhide_working_channels",false),
                isManuallyHidden(c),
                playbackConfirmed=true
            )){
            ensureChannelIndex()
            val updated=manuallyHiddenIds.toMutableSet()
            (channelByKey[channelKey(c)] ?:emptyList()).forEach{updated.remove(id(channels[it]))}
            updated.remove(hiddenChannelId(c))
            prefs.edit().putStringSet("manually_hidden_channels",updated).apply()
            invalidateSurfingCache()
            if(active in channels.indices && channelKey(channels[active])==channelKey(c))
                showBanner("Working channel restored: "+c.name)
        }
        // Record the player that actually reached playback, not a pending fallback hint.
        val engine=if(vlcPlayer!=null)"LibVLC" else "Media3"
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
    // PIN approval unlocks a protected playlist/group for this app session only.
    private val unlockedPlaylistsThisSession=mutableSetOf<String>()
    private val unlockedGroupsThisSession=mutableSetOf<String>()
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
                        invalidateSurfingCache()
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
                                    invalidateSurfingCache()
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
                                invalidateSurfingCache()
                                settings()
                            }.setNegativeButton("Cancel",null).show()
                    }
                }
            }.setNegativeButton("Close",null).show()
    }
    private var retryCount=0
    private var retryUrl:String?=null
    private var retryTask:Runnable?=null
    private fun isFavorite(c:Channel):Boolean{
        ensureChannelIndex()
        return (channelByKey[channelKey(c)] ?: emptyList()).any{favoriteIds.contains(id(channels[it]))}
    }
    private fun favorite(c:Channel){
        ensureChannelIndex()
        val variants=channelByKey[channelKey(c)] ?: listOfNotNull(channels.indexOf(c).takeIf{it>=0})
        val set=favoriteIds.toMutableSet()
        val wasFavorite=variants.any{set.contains(id(channels[it]))}
        if(wasFavorite)variants.forEach{set.remove(id(channels[it]))} else set.add(id(c))
        prefs.edit().putStringSet(viewerKey("favorites"),set).apply()
        invalidateSurfingCache()
        Toast.makeText(this,if(!wasFavorite)"Added to Favorites" else "Removed from Favorites",Toast.LENGTH_SHORT).show()
    }
    private val skippedIds get()=prefs.getStringSet("skip_channel_surfing",emptySet()) ?: emptySet()
    private fun isSkipped(c:Channel):Boolean{
        ensureChannelIndex()
        return (channelByKey[channelKey(c)] ?:emptyList()).any{skippedIds.contains(id(channels[it]))}
    }
    private fun selectedPlaylistSource():String?{
        ensureChannelIndex()
        val enabled=playlists().filter{it.enabled}.map{it.name}
        return HomeTvPolicies.resolvePlaylistSource(
            prefs.getString("selected_playlist_source",null),
            channels.getOrNull(active)?.source,
            enabled,
            channelSources
        )
    }
    private fun playlistIndices():List<Int>{
        val selected=selectedPlaylistSource()
        return visibleIndices().filter{selected==null || channels[it].source==selected}
    }
    private fun playlistIndicesIncludingLocked():List<Int>{
        val selected=selectedPlaylistSource()
        return channelNumbers().filter{selected==null || channels[it].source==selected}
    }
    // Cache the remote-surfing sequence briefly. DPAD repeat events can arrive
    // several times per second; rebuilding and sorting a 10k+ list on every key
    // press caused avoidable allocations and UI stalls on Android TV devices.
    private var surfingCache:List<Int>?=null
    private var surfingCacheAt=0L
    private var surfingCacheOwner:MutableList<Channel>?=null
    private var surfingCacheSize=-1
    private var surfingCacheSource:String?=null
    private var surfingCacheFavorites=false
    private var surfingIndexByChannelKey:Map<String,Int> = emptyMap()
    private fun invalidateSurfingCache(){
        surfingCache=null
        surfingCacheOwner=null
        surfingCacheSize=-1
        surfingIndexByChannelKey=emptyMap()
    }
    private fun surfingIndices():List<Int>{
        val now=android.os.SystemClock.uptimeMillis()
        val favoritesOnly=prefs.getBoolean("favorites_surfing",false)
        val source=selectedPlaylistSource()
        val cached=surfingCache
        if(cached!=null && surfingCacheOwner===channels && surfingCacheSize==channels.size &&
            surfingCacheSource==source && surfingCacheFavorites==favoritesOnly &&
            now-surfingCacheAt<900L)return cached
        val base=playlistIndices().filter{
            !isSkipped(channels[it]) &&
            !isLocked(channels[it]) &&
            !isManuallyHidden(channels[it]) &&
            !isGroupHidden(channels[it].group) &&
            !temporaryFailureHidden.contains(channelKey(channels[it])) &&
            !unavailableChannels.contains(channelKey(channels[it]))
        }
        val filtered=if(favoritesOnly)base.filter{isFavorite(channels[it])} else base
        val result=orderedChannelIndices(if(favoritesOnly)"Favorites" else "All channels",filtered)
        // Build the key-to-position lookup once. DPAD Up/Down then advances
        // directly through the cached sequence instead of scanning 10k+ entries
        // for every remote key press.
        surfingIndexByChannelKey=result.mapIndexed{position,index->
            channelKey(channels[index]) to position
        }.toMap()
        surfingCache=result
        surfingCacheAt=now
        surfingCacheOwner=channels
        surfingCacheSize=channels.size
        surfingCacheSource=source
        surfingCacheFavorites=favoritesOnly
        return result
    }
    private fun toggleChannelSkip(c:Channel){
        val values=skippedIds.toMutableSet()
        val skipped=!isSkipped(c)
        alternateIndices(channels.indexOf(c)).forEach{val entry=channels[it];if(skipped)values.add(id(entry)) else values.remove(id(entry))}
        prefs.edit().putStringSet("skip_channel_surfing",values).apply()
        invalidateSurfingCache()
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
        val returnToPlaybackDrawer=videoScreen?.findViewWithTag<View>("settings_drawer")!=null
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
                if(returnToPlaybackDrawer)pendingPlaybackDrawerChannelKey=selectedKey
                playIndex(target)
            }.setNegativeButton("Cancel",null).create()
        showSideDialog(dialog)
    }
    private fun tryNextAlternative(index:Int):Boolean{
        if(index !in channels.indices)return false
        val key=channelKey(channels[index])
        if(prefs.contains("working_url_"+key) || prefs.contains("manual_url_"+key))return false
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
            ?:return false
        return try{
            val network=manager.activeNetwork ?:return false
            val capabilities=manager.getNetworkCapabilities(network) ?:return false
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }catch(_:Exception){false}
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
    private fun moveAfterFailedChannelHidden(index:Int,screen:FrameLayout,previousSequence:List<Int>){
        if(active!=index || videoScreen!==screen)return
        // Use the exact pre-hide sequence. Once a failed channel is hidden it is
        // absent from the rebuilt cache, so a key-based lookup can lose its
        // position (especially when a playlist contains duplicate channel keys)
        // and incorrectly fall back to the first channel.
        val available=surfingIndices().toHashSet()
        val size=previousSequence.size
        val position=previousSequence.indexOf(index)
        val failedKey=channelKey(channels[index])
        // The active row may be an alternate URL rather than the representative
        // stored in the surfing sequence. Resolve its logical channel position.
        val sequencePosition=if(position>=0)position else previousSequence.indexOfFirst{
            it in channels.indices && channelKey(channels[it])==failedKey
        }
        if(sequencePosition>=0 && size>0){
            val direction=if(surfingDirection>=0)1 else -1
            for(step in 1..size){
                val candidate=previousSequence[(sequencePosition+direction*step+size)%size]
                if(candidate in available && candidate in channels.indices &&
                    channelKey(channels[candidate])!=failedKey){
                    playIndex(preferredChannel(candidate))
                    return
                }
            }
        }
        showHome() // No other surfable channel remains.
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
                val previousSequence=surfingIndices()
                temporaryFailureHidden.add(key)
                unavailableChannels.add(key)
                invalidateSurfingCache()
                moveAfterFailedChannelHidden(index,screen,previousSequence)
            }
            .setNegativeButton("Hide permanently"){_,_->
                failurePromptKey=null
                val previousSequence=surfingIndices()
                if(!isManuallyHidden(c))toggleManualHide(c)
                moveAfterFailedChannelHidden(index,screen,previousSequence)
            }
            .setOnCancelListener{failurePromptKey=null}
            .create()
        dialog.setOnShowListener{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
        }
        dialog.show()
    }
    // Probe alternative streams in isolation. The foreground player is never
    // released or changed by this search. Only a playable decoder result may
    // trigger a user confirmation; HTTP 200 alone is insufficient.
    private val recoveryPending=mutableSetOf<String>()
    private var alternativePrompt:AlertDialog?=null
    private var alternativePromptKey:String?=null
    private fun dismissAlternativePrompt(key:String){
        if(alternativePromptKey!=key)return
        val dialog=alternativePrompt
        alternativePrompt=null
        alternativePromptKey=null
        try{dialog?.dismiss()}catch(_:Exception){}
    }
    private fun checkFailedChannelAlternatives(index:Int,screen:FrameLayout){
        if(index !in channels.indices || active!=index || videoScreen!==screen)return
        val c=channels[index]
        val key=channelKey(c)
        if(!failureSearches.add(key))return
        val candidates=alternateIndices(index).filter{
            it!=index && channels[it].url!=c.url
        }.take(12)
        if(candidates.isEmpty()){failureSearches.remove(key);return}
        val sourceUrl=c.url
        var position=0
        fun stillCurrent():Boolean=
            active in channels.indices && channelKey(channels[active])==key &&
            channels[active].url==sourceUrl && licenseReady && !isFinishing
        fun done(){failureSearches.remove(key)}
        fun offer(candidate:Int,engine:String){
            done()
            if(!stillCurrent() || key !in recoveryPending)return
            dismissAlternativePrompt(key)
            val dialog=AlertDialog.Builder(this)
                .setTitle("Alternative stream available")
                .setMessage(c.name+" — "+engine+". Switch stream?")
                .setPositiveButton("Switch"){_,_->
                    if(!stillCurrent() || key !in recoveryPending)return@setPositiveButton
                    val target=channels[candidate]
                    recoveryPending.remove(key)
                    prefs.edit().putString("manual_url_"+key,target.url)
                        .putString("manual_engine_"+key,engine)
                        .remove("working_url_"+key).remove("confirmed_engine_"+key).apply()
                    fallbackEngineOverride=null
                    recoveryAttempts.remove(sourceUrl)
                    playIndex(candidate)
                }
                .setNegativeButton("Keep current",null)
                .create()
            alternativePrompt=dialog
            alternativePromptKey=key
            dialog.setOnDismissListener{
                if(alternativePrompt===dialog){
                    alternativePrompt=null
                    alternativePromptKey=null
                }
            }
            dialog.show()
            dialog.window?.apply{
                setGravity(android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL)
                setDimAmount(0f)
                addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            }
            handler.postDelayed({
                if(alternativePrompt===dialog) dismissAlternativePrompt(key)
            },8000L)
        }
        fun next(){
            if(!stillCurrent()){done();return}
            if(position>=candidates.size){done();return}
            val candidate=candidates[position++]
            val target=channels[candidate]
            // Use a separate, muted Media3 instance: do not touch the live player.
            var probe:ExoPlayer?=null
            var finished=false
            var watchdog:Runnable?=null
            fun finish(working:Boolean){
                if(finished)return
                finished=true
                watchdog?.let{handler.removeCallbacks(it)}
                try{probe?.release()}catch(_:Exception){}
                probe=null
                if(working)offer(candidate,"Media3")
                else handler.post{probeAlternativeVlc(candidate, target, ::offer, ::next)}
            }
            try{
                val factory=DefaultHttpDataSource.Factory()
                    .setAllowCrossProtocolRedirects(true)
                    .setConnectTimeoutMs(3500).setReadTimeoutMs(4500)
                    .setDefaultRequestProperties(target.headers)
                val exo=ExoPlayer.Builder(this)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build()
                probe=exo
                exo.volume=0f
                exo.addListener(object:Player.Listener{
                    override fun onIsPlayingChanged(isPlaying:Boolean){
                        if(isPlaying)finish(true)
                    }
                    override fun onPlaybackStateChanged(state:Int){
                        if(state==Player.STATE_ENDED)finish(false)
                    }
                    override fun onPlayerError(error:PlaybackException){finish(false)}
                })
                watchdog=Runnable{finish(false)}
                handler.postDelayed(watchdog!!,7000L)
                exo.setMediaItem(mediaItemForStream(target.url))
                exo.prepare()
                exo.play()
            }catch(_:Exception){finish(false)}
        }
        handler.post{next()}
    }
    // Isolated LibVLC compatibility probe; never borrows the foreground player.
    private fun probeAlternativeVlc(
        candidate:Int,target:Channel,
        offer:(Int,String)->Unit,next:()->Unit
    ){
        var core:LibVLC?=null
        var probe:VlcPlayer?=null
        var finished=false
        var watchdog:Runnable?=null
        fun finish(working:Boolean){
            if(finished)return
            finished=true
            watchdog?.let{handler.removeCallbacks(it)}
            try{probe?.stop();probe?.release()}catch(_:Exception){}
            try{core?.release()}catch(_:Exception){}
            probe=null
            core=null
            if(working)offer(candidate,"LibVLC")
            else handler.post{next()}
        }
        try{
            val vlc=LibVLC(this,arrayListOf("--no-audio","--no-video","--network-caching=1200"))
            core=vlc
            val p=VlcPlayer(vlc)
            probe=p
            p.setEventListener{event->
                when(event.type){
                    VlcPlayer.Event.Playing->handler.post{finish(true)}
                    VlcPlayer.Event.EncounteredError,VlcPlayer.Event.EndReached->
                        handler.post{finish(false)}
                }
            }
            val media=VlcMedia(vlc,Uri.parse(target.url))
            media.addOption(":no-audio")
            media.addOption(":no-video")
            target.headers["User-Agent"]?.let{media.addOption(":http-user-agent="+it)}
            target.headers["Referer"]?.let{media.addOption(":http-referrer="+it)}
            p.media=media
            media.release()
            watchdog=Runnable{finish(false)}
            handler.postDelayed(watchdog!!,7000L)
            p.play()
        }catch(e:Exception){HomeTvDiagnostics.w("HomeTV","Alternative VLC probe failed",e);finish(false)}
    }
    private var testingAlternativeKey:String?=null
    // Once a channel has played successfully, the URL and engine are pinned.
    // Recovery never changes either; only a viewer action may replace them.
    private fun handleEngineFailure(index:Int,screen:FrameLayout):Boolean{
        if(index !in channels.indices || active!=index || videoScreen!==screen)return true
        // Do not mark a channel/URL as broken just because the TV lost internet.
        // Wait for connectivity, then retry the same selected channel and engine.
        if(prefs.getBoolean("auto_reconnect",true) && !hasNetworkConnection()){
            awaitNetworkRecovery(index,screen)
            return true
        }
        val c=channels[index]
        val key=channelKey(c)
        val current=if(vlcPlayer!=null)"LibVLC" else "Media3"
        val pinnedUrl=prefs.getString("manual_url_"+key,null)
            ?:prefs.getString("working_url_"+key,null)
        val pinnedEngine=prefs.getString("manual_engine_"+key,null)
            ?:prefs.getString("confirmed_engine_"+key,null)
        val established=pinnedUrl!=null || pinnedEngine in setOf("Media3","LibVLC")
        if(established){
            recoveryPending.add(key)
            // Only a confirmed failure initiates a background alternative search.
            // It must not change the active playback or saved preferences.
            checkFailedChannelAlternatives(index,screen)
            if(!prefs.getBoolean("auto_reconnect",true))return true
            val fixedEngine=pinnedEngine?.takeIf{it in setOf("Media3","LibVLC")} ?:current
            val count=(recoveryAttempts[c.url] ?:0)+1
            recoveryAttempts[c.url]=count
            fallbackEngineOverride=fixedEngine
            // Retry indefinitely but back off to protect decoders and the TV.
            val delay=(2000L*count.coerceAtMost(10)).coerceAtMost(20000L)
            val generation=playbackGeneration
            retryTask?.let{handler.removeCallbacks(it)}
            val task=Runnable{
                if(active==index && videoScreen===screen && playbackGeneration==generation){
                    fallbackEngineOverride=fixedEngine
                    playIndex(index)
                }
            }
            retryTask=task
            handler.postDelayed(task,delay)
            return true
        }
        // Initial setup only: find a working URL/engine combination and save it
        // after a real ready/playing event. No background switch once established.
        val tried=initialTried.getOrPut(key){mutableSetOf()}
        tried.add(c.url+"|"+current)
        val candidates=sequenceOf(index)+alternateIndices(index).asSequence().filter{it!=index}
        val next=candidates.flatMap{i->
            initialEngines(channels[i]).asSequence().map{engine->Pair(i,engine)}
        }.firstOrNull{(i,engine)->!tried.contains(channels[i].url+"|"+engine)}
        if(next!=null){
            fallbackEngineOverride=next.second
            val generation=playbackGeneration
            retryTask?.let{handler.removeCallbacks(it)}
            val task=Runnable{
                if(active==index && videoScreen===screen && playbackGeneration==generation)
                    playIndex(next.first)
            }
            retryTask=task
            handler.postDelayed(task,1000L)
            return true
        }
        if(prefs.getBoolean("auto_skip_nonworking",false)){
            // Capture order before excluding the failed channel. Rebuilding the
            // cache first loses its old position and can send recovery to channel 1.
            val previousSequence=surfingIndices()
            unavailableChannels.add(key)
            invalidateSurfingCache()
            moveAfterFailedChannelHidden(index,screen,previousSequence)
        }else if(!prefs.getBoolean("first_failure_prompted_"+key,false)){
            promptFailedChannel(index,screen)
        }
        return true
    }
    private fun isLocked(c:Channel):Boolean{
        if(lockGroups.contains(c.group) && c.group !in unlockedGroupsThisSession)return true
        ensureChannelIndex()
        return (channelByKey[channelKey(c)] ?:emptyList()).any{lockIds.contains(id(channels[it]))}
    }
    private fun channelNumbers():List<Int>{ensureChannelIndex();return liveChannelRepresentatives}
    // Count logical channels, not multiple stream URLs belonging to the same channel.
    private fun actualChannelCount():Int=channelNumbers().size
    private fun numberFor(index:Int):Int{
        ensureChannelIndex()
        val c=channels.getOrNull(index) ?:return 0
        if(prefs.getBoolean("all_playlists_continuous_numbers",false)){
            return continuousChannelPosition[channelKey(c)] ?:0
        }
        return channelPosition[channelKey(c)] ?:0
    }
    private val manuallyHiddenGroups get()=prefs.getStringSet("manually_hidden_groups",emptySet()) ?: emptySet()
    private fun isGroupHidden(group:String)=manuallyHiddenGroups.contains(group)
    private fun toggleGroupHidden(group:String){
        val updated=manuallyHiddenGroups.toMutableSet()
        if(!updated.add(group))updated.remove(group)
        prefs.edit().putStringSet("manually_hidden_groups",updated).apply()
        invalidateSurfingCache()
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
    private fun visibleIndices():List<Int>{
        val lockedSources=lockedPlaylistNames()
        val lockedGroupsNow=lockedGroupNames()
        return channelNumbers().filter{index->
            val channel=channels[index]
            (channel.source !in lockedSources || channel.source in unlockedPlaylistsThisSession) &&
                (channel.group !in lockedGroupsNow || channel.group in unlockedGroupsThisSession)
        }
    }
    private fun channelLabel(index:Int):String{
        val c=channels[index]
        val marks=(if(isLocked(c))"🔒 " else "")+(if(isSkipped(c))"🙈 " else "")+(if(isFavorite(c))"★ " else "")
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
        val saved=JSONArray(prefs.getString(viewerKey("recent_channels"),"[]"))
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
        val combined=prefs.getBoolean("all_playlists_continuous_numbers",false)
        channelCollection(if(combined)"All channels — Combined" else "All channels — Active playlist",
            if(combined)visibleIndices() else playlistIndices())
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
            Toast.makeText(this,"This programme has already started",Toast.LENGTH_SHORT).show()
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
        val start=upcoming.optLong("start")
        // If a scheduled event becomes due while HomeTV is backgrounded,
        // retain it for the short delivery window and poll at one-minute
        // intervals. A zero-delay loop here would spin continuously in background.
        val delay=if(start<=now && !appInForeground)60_000L
            else (start-now).coerceAtLeast(0L).coerceAtMost(60_000L)
        val task=Runnable{checkEpgTune()}
        epgTuneTask=task
        handler.postDelayed(task,delay)
    }
    private fun checkEpgTune(){
        val saved=JSONArray(prefs.getString("epg_tune_schedules","[]"))
        val now=System.currentTimeMillis()
        val keep=JSONArray()
        val due=mutableListOf<JSONObject>()
        val canDeliver=licenseReady && appInForeground && !isFinishing && !isDestroyed
        for(i in 0 until saved.length()){
            val entry=saved.optJSONObject(i) ?:continue
            val start=entry.optLong("start")
            if(start<=now && start>=now-120000L){
                if(canDeliver)due.add(entry) else keep.put(entry)
            }else if(start>now)keep.put(entry)
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
            if(index>=0 && licenseReady && appInForeground && !isFinishing && !isDestroyed){
                val channelName=channels[index].name
                val target=preferredChannel(index)
                if(entry.optString("mode","reminder")=="auto"){
                    // Auto-switch only when HomeTV is active; do not start
                    // playback in the background or bypass channel locks.
                    playIndex(target)
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
        val raw=HomeTvPolicies.matchEpg(epgGuide,id,name)
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
                    HomeTvDiagnostics.w("HomeTV","EPG source failed: "+url,e)
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
        invalidateSurfingCache()
        Toast.makeText(this,if(shouldHide)"Channel hidden until manually restored" else "Channel restored",Toast.LENGTH_SHORT).show()
    }
    private fun checkHiddenChannelsNow(showResults:Boolean=true){
        // Capture stable channel identities before leaving the UI thread. A
        // playlist refresh can replace the live channel list while checks run.
        val snapshot=channels.toList()
        val hidden=snapshot.filter{c->
            isManuallyHidden(c) || isGroupHidden(c.group) ||
                temporaryFailureHidden.contains(channelKey(c))
        }.distinctBy{channelKey(it)}
        if(hiddenChannelCheckRunning)return
        if(hidden.isEmpty()){
            if(showResults)Toast.makeText(this,"No hidden channels to check",Toast.LENGTH_SHORT).show()
            return
        }
        hiddenChannelCheckRunning=true
        val hiddenKeys=hidden.map{channelKey(it)}.toHashSet()
        val alternateChannelsByKey=snapshot.asSequence()
            .filter{channelKey(it) in hiddenKeys}
            .groupBy{channelKey(it)}
        if(showResults)Toast.makeText(this,"Checking "+hidden.size+" hidden channels in background…",Toast.LENGTH_SHORT).show()
        thread(name="HomeTV-hidden-channel-check"){
            val reachableKeys=mutableListOf<String>()
            // Process a limited group at a time and yield between groups. This
            // keeps large hidden-channel collections from monopolising a worker
            // thread for one uninterrupted scan while preserving sequential,
            // low-bandwidth probing (no burst of simultaneous requests).
            val batches=hidden.chunked(16)
            for((batchIndex,batch) in batches.withIndex()){
                batch.forEach{c->
                    val key=channelKey(c)
                    val urls=alternateChannelsByKey[key].orEmpty().distinctBy{it.url}
                    val reachable=urls.any{candidate->
                        val address=candidate.url.lowercase(java.util.Locale.ROOT)
                        if(!address.startsWith("http://") && !address.startsWith("https://"))return@any false
                        if("youtube.com" in address || "youtu.be" in address)return@any false
                        try{
                            val connection=URL(candidate.url).openConnection() as java.net.HttpURLConnection
                            try{
                                connection.connectTimeout=2500
                                connection.readTimeout=2500
                                connection.instanceFollowRedirects=true
                                connection.setRequestProperty("Range","bytes=0-1023")
                                connection.setRequestProperty("User-Agent",candidate.headers["User-Agent"] ?: "HomeTV/1.0 AndroidTV")
                                candidate.headers.forEach{(header,value)->
                                    if(!header.equals("User-Agent",true))connection.setRequestProperty(header,value)
                                }
                                val code=connection.responseCode
                                val contentType=connection.contentType?.lowercase(java.util.Locale.ROOT) ?: ""
                                HomeTvPolicies.isReachableHttpResponse(code,contentType)
                            }finally{connection.disconnect()}
                        }catch(_:Exception){false}
                    }
                    if(reachable)reachableKeys.add(key)
                }
                // Yield between batches so other background work gets scheduled.
                if(batchIndex<batches.lastIndex)try{Thread.sleep(150L)}catch(_:InterruptedException){Thread.currentThread().interrupt();break}
            }
            handler.post{
                hiddenChannelCheckRunning=false
                if(isFinishing || isDestroyed || !licenseReady)return@post
                ensureChannelIndex()
                val results=reachableKeys.filter{key->channelByKey.containsKey(key)}.distinct()
                if(results.isEmpty()){
                    if(showResults && appInForeground)Toast.makeText(this,"Hidden channel check complete • No reachable channels found in the current playlists",Toast.LENGTH_LONG).show()
                    return@post
                }
                if(!appInForeground || !showResults){
                    val pending=prefs.getStringSet("pending_hidden_channel_results",emptySet()).orEmpty().toMutableSet()
                    pending.addAll(results)
                    prefs.edit().putStringSet("pending_hidden_channel_results",pending).apply()
                    if(appInForeground)showPendingHiddenChannelPrompt()
                    return@post
                }
                showPendingHiddenChannelPrompt(results)
            }
        }
    }
    private fun showPendingHiddenChannelPrompt(newResults:List<String>?=null){
        if(!appInForeground || isFinishing || isDestroyed)return
        ensureChannelIndex()
        val pending=(newResults ?: prefs.getStringSet("pending_hidden_channel_results",emptySet()).orEmpty().toList())
            .filter{key->channelByKey.containsKey(key)}
            .distinct()
        if(pending.isEmpty())return
        prefs.edit().remove("pending_hidden_channel_results").apply()
        val count=pending.size
        AlertDialog.Builder(this)
            .setTitle("Reachable hidden channel URLs found")
            .setMessage("HomeTV found $count hidden channel(s) whose stream URLs responded. This is a reachability check, not proof of video playback. Unhiding is your choice.")
            .setPositiveButton("Unhide All"){_,_->
                ensureChannelIndex()
                val updated=manuallyHiddenIds.toMutableSet()
                pending.forEach{key->
                    for(index in channelByKey[key].orEmpty()){
                        val channel=channels.getOrNull(index) ?:continue
                        updated.remove(id(channel));updated.remove(hiddenChannelId(channel))
                    }
                    temporaryFailureHidden.remove(key);unavailableChannels.remove(key)
                }
                prefs.edit().putStringSet("manually_hidden_channels",updated).apply()
                invalidateSurfingCache()
                val groupCount=pending.count{key->
                    channelByKey[key].orEmpty().any{index->
                        channels.getOrNull(index)?.let{isGroupHidden(it.group)}==true
                    }
                }
                Toast.makeText(this,"Restored $count reachable channel(s)."+if(groupCount>0)" Some remain hidden by group settings." else "",Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Review"){_,_->showHiddenChannelReview(pending)}
            .setNegativeButton("Later"){_,_->
                val remaining=prefs.getStringSet("pending_hidden_channel_results",emptySet()).orEmpty().toMutableSet()
                remaining.addAll(pending);prefs.edit().putStringSet("pending_hidden_channel_results",remaining).apply()
            }.show()
    }
    private fun showHiddenChannelReview(keys:List<String>){
        ensureChannelIndex()
        val remaining=keys.filter{channelByKey.containsKey(it)}.toMutableList()
        fun matching(key:String)=channelByKey[key].orEmpty().mapNotNull{channels.getOrNull(it)}
        val labels=remaining.map{key->matching(key).firstOrNull()?.name.orEmpty()+"  —  Unhide"}.toMutableList()
        val adapter=android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,labels)
        val dialog=AlertDialog.Builder(this).setTitle("Reachable hidden channel URLs • "+remaining.size)
            .setAdapter(adapter){_,_->}
            .setPositiveButton("Unhide All"){_,_->
                ensureChannelIndex()
                val updated=manuallyHiddenIds.toMutableSet()
                remaining.forEach{key->
                    for(index in channelByKey[key].orEmpty()){
                        val channel=channels.getOrNull(index) ?:continue
                        updated.remove(id(channel));updated.remove(hiddenChannelId(channel))
                    }
                    temporaryFailureHidden.remove(key);unavailableChannels.remove(key)
                }
                prefs.edit().putStringSet("manually_hidden_channels",updated).apply()
                invalidateSurfingCache()
                Toast.makeText(this,"Selected reachable channels restored",Toast.LENGTH_LONG).show()
            }.setNegativeButton("Close",null).create()
        dialog.setOnShowListener{
            dialog.listView?.setOnItemClickListener{_,_,position,_->
                val key=remaining.getOrNull(position)?:return@setOnItemClickListener
                ensureChannelIndex()
                val c=matching(key).firstOrNull()?:return@setOnItemClickListener
                AlertDialog.Builder(this).setTitle(c.name)
                    .setMessage("The stream URL responded successfully, but video playback is not verified.")
                    .setPositiveButton("Unhide"){_,_->
                        ensureChannelIndex()
                        val updated=manuallyHiddenIds.toMutableSet()
                        for(index in channelByKey[key].orEmpty()){
                            val channel=channels.getOrNull(index) ?:continue
                            updated.remove(id(channel));updated.remove(hiddenChannelId(channel))
                        }
                        prefs.edit().putStringSet("manually_hidden_channels",updated).apply()
                        temporaryFailureHidden.remove(key);unavailableChannels.remove(key)
                        invalidateSurfingCache()
                        remaining.removeAt(position);labels.removeAt(position);adapter.notifyDataSetChanged()
                        dialog.setTitle("Reachable hidden channel URLs • "+remaining.size)
                    }.setNegativeButton("Keep hidden",null).show()
            }
        }
        showSideDialog(dialog)
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
        channelCollection("Favorites",visibleIndices().filter{!isManuallyHidden(channels[it]) && !isGroupHidden(channels[it].group) && isFavorite(channels[it])})
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
                    2->withPinSetup {
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
            setBackgroundColor(0xFF0B1B2B.toInt())
        }
        // Build row text only when ListView requests a visible/recycled row.
        // Do not allocate a second 10k+ String list for large playlists.
        list.adapter=object:android.widget.BaseAdapter(){
            override fun getCount()=indices.size
            override fun getItem(position:Int):Any=channels[indices[position]]
            override fun getItemId(position:Int)=indices[position].toLong()
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View{
                val view=(convertView as? TextView) ?: TextView(this@MainActivity).apply{
                    textSize=18f
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(tvDp(22),tvDp(17),tvDp(18),tvDp(17))
                    minHeight=tvDp(62)
                    background=tvBg()
                    setOnFocusChangeListener{v,focus->
                        v.background=tvBg(if(focus)0xFF18628C.toInt() else 0xFF152A43.toInt())
                    }
                    isSingleLine=true
                    ellipsize=android.text.TextUtils.TruncateAt.END
                }
                val index=indices[position]
                view.text=channelLabel(index)+"  ·  "+channels[index].group
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
            HomeTvDiagnostics.i("HomeTV","Adaptive buffer increased to level "+autoBufferLevel)
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
    private fun bufferProfile():String{
        val channel=channels.getOrNull(active)
        val override=channel?.let{prefs.getString("buffer_channel_"+channelKey(it),null)}
        return override ?: (prefs.getString("buffer_profile","Auto") ?: "Auto")
    }
    private fun bufferValues():IntArray=when(bufferProfile()){
        "Auto"->bufferLevelValues(autoBufferLevel)
        "Ultra Fast"->intArrayOf(500,3500,250,500)
        "Fast"->intArrayOf(1000,7000,500,1000)
        "Stable"->intArrayOf(4000,25000,1500,3500)
        "Maximum Buffer"->intArrayOf(8000,45000,3000,6500)
        else->intArrayOf(2000,15000,1000,2000)
    }
    private fun chooseChannelBufferProfile(index:Int){
        val c=channels.getOrNull(index) ?:return
        val key="buffer_channel_"+channelKey(c)
        val options=arrayOf("Use universal default")+bufferProfiles
        val current=prefs.getString(key,null)
        val selected=if(current==null)0 else options.indexOf(current).coerceAtLeast(0)
        var pending=selected
        val dialog=AlertDialog.Builder(this).setTitle(c.name+" — Buffering")
            .setSingleChoiceItems(options,selected){_,choice->pending=choice}
            .setPositiveButton("OK"){_,_->
                prefs.edit().apply{
                    if(pending==0)remove(key)
                    else putString(key,bufferProfiles[pending-1])
                }.apply()
                showBanner("Buffer setting saved • applies when channel next starts")
            }.setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{
            val list=dialog.listView ?:return@setOnShowListener
            list.choiceMode=android.widget.ListView.CHOICE_MODE_SINGLE
            list.setItemChecked(selected,true);list.setSelection(selected)
            list.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){pending=position;list.setItemChecked(position,true)}
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            }
        }
        dialog.show()
    }
    private fun chooseBufferProfile(){
        val returnToSettings=settingsScreenVisible
        val returnToPlaybackDrawer=videoScreen?.findViewWithTag<View>("settings_drawer")!=null
        var pending=bufferProfiles.indexOf(prefs.getString("buffer_profile","Auto")).coerceAtLeast(0)
        val dialog=AlertDialog.Builder(this).setTitle("Buffering profile")
            .setSingleChoiceItems(bufferProfiles,pending){_,i->pending=i}
            .setPositiveButton("OK"){_,_->
                prefs.edit().putString("buffer_profile",bufferProfiles[pending]).apply()
                if(returnToSettings)settings()
                else if(!returnToPlaybackDrawer && active>=0)showPlaybackSettings()
                else if(!returnToPlaybackDrawer)settings()
            }.setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{
            val list=dialog.listView ?:return@setOnShowListener
            list.choiceMode=android.widget.ListView.CHOICE_MODE_SINGLE
            list.setItemChecked(pending,true);list.setSelection(pending)
            list.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){pending=position;list.setItemChecked(position,true)}
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            }
        }
        dialog.show()
    }
    // Stream routing is independent of the user-selected engine and never
    // overrides a confirmed working URL/engine or a manual engine selection.
    private fun streamKind(url:String):String{
        val uri=try{Uri.parse(url.trim())}catch(_:Exception){return "unknown"}
        val scheme=uri.scheme?.lowercase(java.util.Locale.ROOT) ?:return "unknown"
        val path=uri.path.orEmpty().lowercase(java.util.Locale.ROOT)
        val query=uri.query.orEmpty().lowercase(java.util.Locale.ROOT)
        return when{
            scheme=="rtsp" || scheme=="rtsps"->"rtsp"
            scheme=="rtmp" || scheme=="rtmps" || scheme=="rtmpt"->"rtmp"
            scheme=="udp" || scheme=="rtp" || scheme=="mms" || scheme=="mmsh"->"vlc"
            scheme!="http" && scheme!="https"->"unknown"
            path.endsWith(".m3u8") || query.contains("format=m3u8")->"hls"
            path.endsWith(".mpd") || query.contains("format=mpd")->"dash"
            path.endsWith(".ism/manifest") || path.endsWith(".isml/manifest")->"smooth"
            path.endsWith(".mp4") || path.endsWith(".m4v") ||
                path.endsWith(".mkv") || path.endsWith(".webm") ||
                path.endsWith(".ts") || path.endsWith(".mp3") ||
                path.endsWith(".aac") || path.endsWith(".flv")->"file"
            path.endsWith(".swf")->"flash"
            else->"http"
        }
    }
    // Media3 is the normal engine; LibVLC remains first for RTMP/VLC-only protocols.
    private fun initialEngines(c:Channel):List<String> = when(streamKind(c.url)){
        "rtmp","vlc"->listOf("LibVLC","Media3")
        else->listOf("Media3","LibVLC")
    }
    private fun engineFor(c:Channel):String{
        // Explicit channel choice must win over any stale automatic fallback.
        prefs.getString("manual_engine_"+channelKey(c),null)?.let{
            if(it=="Media3" || it=="LibVLC")return it
        }
        if(prefs.getString("working_url_"+channelKey(c),null)==c.url){
            prefs.getString("confirmed_engine_"+channelKey(c),null)?.let{
                if(it in setOf("Media3","LibVLC"))return it
            }
        }
        fallbackEngineOverride?.let{return it}
        val universal=prefs.getString("engine","Auto") ?: "Auto"
        if(prefs.getBoolean("libvlc_only_"+hash(c.url),false))return "LibVLC"
        if(universal=="Media3" || universal=="LibVLC")return universal
        return initialEngines(c).first()
    }
    private fun chooseChannelEngine(i:Int){
        if(i !in channels.indices)return
        val returnToPlaybackDrawer=videoScreen?.findViewWithTag<View>("settings_drawer")!=null
        val c=channels[i]
        val universal=(prefs.getString("engine","Media3") ?: "Media3")
            .takeIf{it=="Media3" || it=="LibVLC"} ?: "Auto"
        val choices=arrayOf("Auto (Universal: "+universal+")","Media3","LibVLC")
        val selected=when(prefs.getString("manual_engine_"+channelKey(c),null)){
            "Media3"->1
            "LibVLC"->2
            else->0
        }
        var pending=selected
        val dialog=AlertDialog.Builder(this).setTitle(c.name+" — Player")
            .setSingleChoiceItems(choices,selected){_,which->pending=which}
            .setPositiveButton("OK"){_,_->
                prefs.edit().apply{
                    if(pending==0)remove("manual_engine_"+channelKey(c))
                    else putString("manual_engine_"+channelKey(c),choices[pending])
                }.apply()
                engineAttempts.clear();fallbackEngineOverride=null;unavailableChannels.remove(channelKey(c))
                if(active==i){
                    if(returnToPlaybackDrawer)pendingPlaybackDrawerChannelKey=channelKey(c)
                    playIndex(i)
                }
            }.setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{
            val list=dialog.listView ?:return@setOnShowListener
            list.choiceMode=android.widget.ListView.CHOICE_MODE_SINGLE
            list.setItemChecked(selected,true);list.setSelection(selected)
            list.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){pending=position;list.setItemChecked(position,true)}
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            }
        }
        dialog.show()
    }
    private fun channelOptions(i:Int){
        if(i !in channels.indices)return
        val c=channels[i]
        val opts=arrayOf(
            "Watch channel",
            "Aspect ratio",
            "Stream quality",
            "Audio track",
            "Preferred audio language",
            "Subtitle settings",
            "Sleep timer",
            "Playback engine",
            "Select stream source",
            if(favoriteIds.contains(id(c)))"Remove favorite" else "Add favorite",
            "Parental lock",
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
                    4->choosePreferredAudioLanguage()
                    5->subtitleTracks(i)
                    6->sleepTimer()
                    7->chooseChannelEngine(i)
                    8->selectAlternative(i)
                    9->{favorite(c);if(active<0)allChannels()}
                    10->toggleLock(id(c),false)
                    11->{toggleManualHide(c);if(active<0)allChannels()}
                    12->allChannels()
                    13->liveSettings()
                    14->confirmExit()
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
        if(licenseReady)onLicenseNetworkAvailable()
        if(licenseReady){
            showPendingHiddenChannelPrompt()
            scheduleStartupHiddenChannelScan()
        }
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
        // Resume the suspended channel first. Otherwise an EPG auto-tune
        // can be immediately overwritten by the old channel on return.
        if(licenseReady)checkEpgTune() else armEpgTune()
    }
    override fun onPause(){
        val wasPlaying=active in channels.indices && !isFinishing
        appInForeground=false
        if(wasPlaying){
            val current=channels[active]
            suspendedPlaybackUrl=current.url
            suspendedPlaybackIdentity=id(current)
            // A low-priority hidden-channel scan may continue on its worker
            // while the app is backgrounded; it never controls playback.
            if(licenseReady && !safeStartupMode &&
                prefs.getBoolean("auto_hidden_channel_check",true) &&
                prefs.getBoolean("background_hidden_channel_check",true) &&
                !hiddenChannelCheckRunning)checkHiddenChannelsNow(showResults=false)
            // stopPlayback saves VOD progress and releases Media3, LibVLC and WebView.
            stopPlayback()
        }
        super.onPause()
    }
    private fun migrateLegacyIJKPreferences(){
        val editor=prefs.edit()
        var changed=false
        prefs.all.forEach{(key,value)->
            if(key=="engine" && value=="IJKPlayer"){
                editor.putString("engine","Auto")
                changed=true
            }else if(value=="IJKPlayer" && key.startsWith("manual_engine_")){
                editor.remove(key)
                changed=true
            }else if(value=="IJKPlayer" && key.startsWith("confirmed_engine_")){
                editor.remove(key)
                editor.remove("working_url_"+key.removePrefix("confirmed_engine_"))
                changed=true
            }
        }
        if(changed)editor.apply()
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        safeStartupMode=HomeTvDiagnostics.initialize(this)
        migrateLegacyIJKPreferences()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(14,12,14,12) }
        root.setBackgroundColor(0xFF101E31.toInt())
        setContentView(android.widget.ScrollView(this).apply{isFillViewport=true;addView(root)})
        beginLicensedSession()
        registerLicenseNetworkCallback()
    }
    private fun label(s:String)=TextView(this).apply { text=s; textSize=23f; setPadding(18,16,18,16); setTextColor(android.graphics.Color.WHITE) }
    private fun button(s:String, action:()->Unit)=Button(this).apply {
        text=s;isAllCaps=false;textSize=17f
        minHeight=tvDp(54)
        setPadding(tvDp(18),tvDp(10),tvDp(18),tvDp(10))
        setTextColor(android.graphics.Color.WHITE)
        backgroundTintList=android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_pressed),
                intArrayOf()
            ),
            intArrayOf(0xFF5FE6CC.toInt(),0xFF5FE6CC.toInt(),0xFF173550.toInt())
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
        homeScreenVisible=false
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
        settingsScreenVisible=false
        stopPlayback()
        window.decorView.systemUiVisibility=0
        root.setBackgroundColor(0xFF091421.toInt())
        root.setPadding(tvDp(22),tvDp(12),tvDp(22),tvDp(22))
        setContentView(android.widget.ScrollView(this).apply{
            isFillViewport=true
            setBackgroundColor(0xFF091421.toInt())
            addView(root.also{(it.parent as? android.view.ViewGroup)?.removeView(it)})
        })
        root.removeAllViews()
        root.addView(label(title))
    }
    private fun playlists():MutableList<Playlist> {
        val arr=JSONArray(prefs.getString("playlists","[]"))
        // Use the existing TV.m3u feed directly; no Master playlist or build-time
        // playlist variable is required.
        val tvFeed="https://raw.githubusercontent.com/my-hometv/tv/main/TV.m3u"
        val previousMasterFeed="https://raw.githubusercontent.com/my-hometv/tv/main/Master.m3u"
        var migrated=false
        var tvAdded=false
        val configured=mutableListOf<Playlist>()
        for(i in 0 until arr.length()) {
            val o=arr.getJSONObject(i)
            val original=o.getString("url")
            val name=o.optString("name","")
            val normalized=original.substringBefore('?').substringBefore('#').trimEnd('/')
            val isPreviousFeed=name.equals("Master",true) ||
                normalized.equals(tvFeed,true) ||
                normalized.equals(previousMasterFeed,true) ||
                normalized.equals("https://github.com/my-hometv/tv/blob/main/TV.m3u",true) ||
                normalized.equals("https://github.com/my-hometv/tv/blob/main/Master.m3u",true)
            if(isPreviousFeed) {
                if(!tvAdded) {
                    configured.add(Playlist("TV",tvFeed,o.optBoolean("enabled",true)))
                    tvAdded=true
                }
                migrated=true
            } else {
                configured.add(Playlist(name,original,o.optBoolean("enabled",true)))
            }
        }
        if(!tvAdded) {
            configured.add(0,Playlist("TV",tvFeed,true))
            migrated=true
        }
        val result=mutableListOf(Playlist("HomeTV Live",licensedPlaylist)).apply{addAll(configured)}
        val selected=prefs.getString("selected_playlist_source",null)
        if(selected==null || selected.equals("TV",true) || selected.equals("Master",true)) {
            prefs.edit().putString("selected_playlist_source","TV").apply()
        }
        if(migrated)savePlaylists(result)
        return result
    }
    private fun savePlaylists(list:List<Playlist>) {
        val arr=JSONArray(); list.filter{it.url!=licensedPlaylist}.forEach { arr.put(JSONObject().put("name",it.name).put("url",it.url).put("enabled",it.enabled)) }
        prefs.edit().putString("playlists",arr.toString()).apply()
    }

    // My Home TV media library. Unmarked EXTINF entries are always Live TV.
    private fun mediaItems(type:String)=visibleIndices().filter{channels[it].mediaType==type}
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
                if(bmp!=null && !isFinishing && !isDestroyed){
                    posterCache.put(url,bmp)
                    runOnUiThread{
                        if(!isFinishing && !isDestroyed && image.tag==url)
                            image.setImageBitmap(bmp)
                    }
                }
            }catch(_:Exception){}finally{posterLoading.remove(url)}
        }
    }
    private fun youtubeLibrary(){
        val items=channels.indices.filter{isYoutubeChannel(it)}
        if(items.isEmpty()){
            channelCollection("YouTube",emptyList())
            return
        }
        mediaRows("YouTube",items)
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
        val indices=visibleIndices().filter{channels[it].mediaType in setOf("movie","series") && mediaResumeSeconds(channels[it])>0}
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


    // V6 cinematic Android TV components. Remote focus never relies on touch.
    private fun tvDp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun tvBg(color:Int=0xFF152A43.toInt()):android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply{
            setColor(color);cornerRadius=tvDp(16).toFloat()
            setStroke(tvDp(1),0xFF356080.toInt())
        }
    private fun tvText(t:String,size:Float=17f,color:Int=android.graphics.Color.WHITE)=
        TextView(this).apply{text=t;textSize=size;setTextColor(color);gravity=Gravity.CENTER_VERTICAL}
    private fun tvSection(t:String)=tvText(t,19f,0xFF6CE4FF.toInt()).apply{
        setPadding(tvDp(3),tvDp(20),0,tvDp(12))
    }
    private fun tvCard(icon:String,title:String,detail:String,action:()->Unit):LinearLayout =
        LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(tvDp(18),tvDp(13),tvDp(12),tvDp(12))
            background=tvBg();isFocusable=true;isClickable=true
            addView(tvText(icon,30f,0xFF6CE4FF.toInt()))
            addView(tvText(title,19f).apply{setPadding(0,tvDp(6),0,0)})
            addView(tvText(detail,12f,0xFFB0C6D9.toInt()).apply{maxLines=2;setPadding(0,tvDp(4),0,0)})
            setOnFocusChangeListener{v,focus->
                v.background=tvBg(if(focus)0xFF18628C.toInt() else 0xFF152A43.toInt())
                v.scaleX=if(focus)1.04f else 1f
                v.scaleY=if(focus)1.04f else 1f
                v.elevation=tvDp(if(focus)10 else 2).toFloat()
            }
            setOnClickListener{action()}
        }
    private fun tvRow(vararg cards:LinearLayout)=LinearLayout(this).apply{
        orientation=LinearLayout.HORIZONTAL
        cards.forEach{addView(it,LinearLayout.LayoutParams(0,tvDp(146),1f).apply{marginEnd=tvDp(12)})}
    }
    private var homeScreenVisible=false
    private fun showHome(){
        reset("My Home TV  •  HOME")
        homeScreenVisible=true

        // A fixed navigation rail stays visible while the cinematic home rows scroll.
        val homeLayout=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF091421.toInt())
        }
        val navigation=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.TOP
            setPadding(tvDp(12),tvDp(18),tvDp(12),tvDp(16))
            setBackgroundColor(0xFF0B1B2B.toInt())
        }
        navigation.addView(tvText("PPJSoft",16f,0xFF9CB4C9.toInt()).apply{
            setPadding(tvDp(10),tvDp(8),tvDp(8),0)
        })
        navigation.addView(tvText("MY HOME TV",21f,0xFF92E9FF.toInt()).apply{
            setPadding(tvDp(10),0,tvDp(8),tvDp(18))
        })
        fun navigationButton(title:String,action:()->Unit){
            val item=button(title,action).apply{
                textSize=15f
                minHeight=tvDp(48)
                gravity=Gravity.CENTER_VERTICAL
            }
            navigation.addView(item,LinearLayout.LayoutParams(-1,-2).apply{
                bottomMargin=tvDp(6)
            })
        }
        navigationButton("⌂  Home"){showHome()}
        navigationButton("▦  Live TV"){allChannels()}
        navigationButton("★  Favorites"){favorites()}
        navigationButton("▤  TV Guide"){showEpg()}
        navigationButton("▣  Movies"){mediaLibrary("movie")}
        navigationButton("▤  Series"){mediaSeries()}
        navigationButton("◉  Web TV"){mediaLibrary("web")}
        navigationButton("▶  YouTube"){youtubeLibrary()}
        navigationButton("＋  Playlists"){managePlaylists()}
        navigationButton("⚙  Settings"){settings()}

        val homeContent=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(tvDp(27),tvDp(18),tvDp(27),tvDp(24))
        }
        val contentScroll=android.widget.ScrollView(this).apply{
            isFillViewport=true
            isFocusable=false
            addView(homeContent)
        }
        homeLayout.addView(navigation,LinearLayout.LayoutParams(tvDp(190),-1))
        homeLayout.addView(contentScroll,LinearLayout.LayoutParams(0,-1,1f))
        setContentView(homeLayout)

        val hero=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(tvDp(24),tvDp(20),tvDp(24),tvDp(20))
            background=android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF16466C.toInt(),0xFF0A1728.toInt())
            ).apply{cornerRadius=tvDp(19).toFloat()}
            addView(tvText("⌂  MY HOME TV",32f,0xFF92E9FF.toInt()))
            addView(tvText("Your entertainment. Your way.",16f,0xFFB7C8D8.toInt()))
            addView(tvText(actualChannelCount().toString()+" LIVE CHANNELS   •   "+viewerId().uppercase(),14f,0xFF5FE6CC.toInt()))
            addView(tvText("BY PPJSoft",12f,0xFFB7C8D8.toInt()).apply{gravity=Gravity.RIGHT})
        }
        homeContent.addView(hero)
        homeContent.addView(tvSection("WATCH NOW"))
        homeContent.addView(tvRow(
            tvCard("▶","Continue TV","Resume last channel"){if(channels.isEmpty())refresh({resumeLast()})else resumeLast()},
            tvCard("▦","Live channels","Browse all channels"){allChannels()},
            tvCard("★","Favorites","Your saved channels"){favorites()},
            tvCard("▤","TV guide","Programmes and reminders"){showEpg()}
        ))
        homeContent.addView(tvSection("EXPLORE"))
        homeContent.addView(tvRow(
            tvCard("▣","Movies","Movie library"){mediaLibrary("movie")},
            tvCard("▤","Series","Episodes and shows"){mediaSeries()},
            tvCard("♫","Music","Music library"){mediaMusic()},
            tvCard("◉","Web TV","Web channels"){mediaLibrary("web")}
        ))
        homeContent.addView(tvSection("YOUTUBE"))
        homeContent.addView(tvRow(
            tvCard("▶","YouTube","Videos and live streams"){youtubeLibrary()}
        ))
        homeContent.addView(tvSection("DISCOVER"))
        homeContent.addView(tvRow(
            tvCard("⌕","Search","Find channels"){searchChannels()},
            tvCard("☷","Categories","Channel groups"){showChannels()},
            tvCard("◷","History","Recently watched"){recentlyWatched()},
            tvCard("▶","Continue media","Resume films and series"){continueMedia()}
        ))
        homeContent.addView(tvSection("TOOLS"))
        homeContent.addView(tvRow(
            tvCard("＋","Playlists","Manage sources"){managePlaylists()},
            tvCard("↻","Refresh","Rebuild playlists"){refresh({showHome()},true)},
            tvCard("⚙","Settings","Personalize HomeTV"){settings()},
            tvCard("🙈","Hidden","Hidden channels"){hiddenChannels()}
        ))
        homeContent.post{(homeContent.getChildAt(2) as? LinearLayout)?.getChildAt(0)?.requestFocus()}
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
        // Large M3U imports must never parse or write files on the Android UI thread.
        thread(name="HomeTV-playlist-import"){
            val valid=try{parseM3u(body,title).isNotEmpty()}catch(e:Exception){false}
            if(!valid){runOnUiThread{Toast.makeText(this,"No valid M3U channels found",Toast.LENGTH_LONG).show()};return@thread}
            val result=try{
                val file=java.io.File(filesDir,"import_"+System.currentTimeMillis()+".m3u")
                file.writeText(body)
                file.toURI().toString()
            }catch(e:Exception){
                runOnUiThread{Toast.makeText(this,"Import failed: "+e.message,Toast.LENGTH_LONG).show()}
                return@thread
            }
            runOnUiThread{
                if(isFinishing || isDestroyed)return@runOnUiThread
                val list=playlists()
                list.add(Playlist(title,result))
                savePlaylists(list)
                refresh({managePlaylists()},true)
            }
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
        if(requestCode==exportBackupRequest || requestCode==exportDiagnosticsRequest){
            val payload=pendingTextExport
            pendingTextExport=null
            if(resultCode!=RESULT_OK || data?.data==null || payload==null)return
            try{
                contentResolver.openOutputStream(data.data!!,"wt")?.bufferedWriter(Charsets.UTF_8)?.use{it.write(payload)}
                    ?:throw IllegalStateException("Cannot write to the selected destination")
                Toast.makeText(this,"Saved successfully.",Toast.LENGTH_LONG).show()
            }catch(e:Exception){
                Toast.makeText(this,"Save failed: "+e.message,Toast.LENGTH_LONG).show()
            }
            return
        }
        if(requestCode==importBackupRequest){
            if(resultCode!=RESULT_OK)return
            val uri=data?.data ?:return
            thread{
                try{
                    val raw=contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use{it.readText()}
                        ?:throw IllegalArgumentException("Cannot read selected backup")
                    runOnUiThread{restoreSettingsFromText(raw)}
                }catch(e:Exception){
                    runOnUiThread{Toast.makeText(this,"Backup could not be opened: "+e.message,Toast.LENGTH_LONG).show()}
                }
            }
            return
        }
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
        val locked=prefs.getStringSet("locked_playlists",emptySet())?.toMutableSet() ?: mutableSetOf()
        list.forEachIndexed { index,p ->
            val isLocked=p.name in locked
            val row=button((if(isLocked)"🔒 " else if(p.enabled)"✓ " else "○ ")+p.name+" — "+(if(p.url.contains("/get.php?"))"Xtream Codes" else if(p.url.startsWith("file:"))"Local M3U" else p.url.take(40))){
                val openOptions={
                    val opts=arrayOf("Enable / Disable","Edit","Delete",if(isLocked)"Unlock playlist" else "Lock playlist")
                    AlertDialog.Builder(this).setTitle(p.name).setItems(opts){_,which->
                        when(which){
                            0->{list[index]=p.copy(enabled=!p.enabled);savePlaylists(list);managePlaylists()}
                            1->editPlaylist(index)
                            2->AlertDialog.Builder(this).setMessage("Delete playlist?").setPositiveButton("Delete"){_,_->
                                val removed=list.removeAt(index)
                                deletePlaylistSource(removed.name)
                            }.setNegativeButton("Cancel",null).show()
                            3->{
                                val current=prefs.getStringSet("locked_playlists",emptySet())?.toMutableSet() ?: mutableSetOf()
                                if(p.name in current)current.remove(p.name) else current.add(p.name)
                                prefs.edit().putStringSet("locked_playlists",current).apply()
                                Toast.makeText(this,if(p.name in current)"Playlist locked" else "Playlist unlocked",Toast.LENGTH_SHORT).show()
                                managePlaylists()
                            }
                        }
                    }.show()
                }
                if(isLocked)requirePin({openOptions()},"playlist:"+p.name){deletePlaylistAfterPinFailures(p.name)} else openOptions()
            }
            row.setOnLongClickListener{
                val changeLock={
                    val current=prefs.getStringSet("locked_playlists",emptySet())?.toMutableSet() ?: mutableSetOf()
                    if(p.name in current)current.remove(p.name) else current.add(p.name)
                    prefs.edit().putStringSet("locked_playlists",current).apply()
                    Toast.makeText(this,if(p.name in current)"Playlist locked" else "Playlist unlocked",Toast.LENGTH_SHORT).show()
                    managePlaylists()
                }
                withPinSetup{changeLock()}
                true
            }
            root.addView(row)
        }
        root.addView(button("Channel list scope: "+if(prefs.getBoolean("all_playlists_continuous_numbers",false))"All enabled playlists (continuous numbers)" else "Active playlist only"){
            val combined=!prefs.getBoolean("all_playlists_continuous_numbers",false)
            prefs.edit().putBoolean("all_playlists_continuous_numbers",combined).apply()
            managePlaylists()
        })
        root.addView(button("↻ Refresh playlists"){refresh({managePlaylists()},true)})
        root.addView(button("Add playlist URL"){editPlaylist(null)})
        root.addView(button("Import playlist file (USB / device)"){showImportFallback()})
        root.addView(button("Add Xtream Codes account"){addXtream()})
        root.addView(button("Back"){showHome()})
    }
    private fun editPlaylist(index:Int?,fromPlaybackDrawer:Boolean=false){
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
                refresh({
                    if(fromPlaybackDrawer){
                        // Stay in the TV player; show the newly added playlist's channels.
                        prefs.edit().putString("selected_playlist_source",n).apply()
                        if(videoScreen!=null && active>=0)showPlaylistDrawer()
                        else showHome()
                    }else managePlaylists()
                })
            } else Toast.makeText(this,"Enter name and HTTP(S) URL",Toast.LENGTH_LONG).show()
        }.setNegativeButton("Cancel",null).show()
    }
    private fun playlistCacheKey(p:Playlist):String =
        MessageDigest.getInstance("SHA-256").digest(p.url.toByteArray())
            .joinToString(""){"%02x".format(it)}
    private fun cacheFile(p:Playlist):java.io.File =
        java.io.File(filesDir,"playlist_"+playlistCacheKey(p)+".m3u")
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
        val sources=playlists().filter{it.enabled}
        if(sources.isEmpty())return mutableListOf()
        // Parse cached playlists concurrently on a small bounded pool. The TV
        // UI stays free, and startup never waits for network playlist refreshes.
        val results=Array(sources.size){emptyList<Channel>()}
        val pool=java.util.concurrent.Executors.newFixedThreadPool(minOf(3,sources.size))
        try{
            val tasks=sources.mapIndexed{index,p->
                pool.submit{
                    try{
                        val file=cacheFile(p)
                        if(file.exists())results[index]=parseM3u(readPlaylistCache(file),p.name)
                    }catch(e:Exception){
                        HomeTvDiagnostics.w("HomeTV","Playlist cache read failed: "+p.name,e)
                    }
                }
            }
            tasks.forEach{it.get()}
        }finally{
            pool.shutdown()
        }
        return results.asSequence().flatten()
            .distinctBy{it.source.lowercase()+"|"+it.name.lowercase()+"|"+it.url}
            .toMutableList()
    }
    private fun refresh(done:()->Unit,forceRebuild:Boolean=false,activeOnly:Boolean=false){
        if(!licenseReady){showLicenseScreen("Please activate your license");return}
        val configured=playlists().filter{it.enabled}
        val activeSource=selectedPlaylistSource()
            ?:channels.getOrNull(active)?.source
            ?:configured.firstOrNull()?.name
        val sources=configured.filter{!activeOnly || it.name==activeSource}
        if(sources.isEmpty()){
            Toast.makeText(this,"No enabled playlists. Existing channels preserved.",Toast.LENGTH_LONG).show()
            done()
            return
        }
        Toast.makeText(this,if(forceRebuild)"Rebuilding playlists from source…" else "Checking "+sources.size+" playlists for updates…",Toast.LENGTH_LONG).show()
        // Take an immutable reference snapshot; grouping thousands of channels
        // on the TV UI thread causes visible freezes during refresh.
        val previousChannels=channels.toList()
        val generation=++refreshGeneration
        thread(name="HomeTV-playlist-refresh"){
            val previousBySource=previousChannels.groupBy{it.source}
            val results=Array(sources.size){emptyList<Channel>()}
            val refreshed=BooleanArray(sources.size)
            val checked=BooleanArray(sources.size)
            // A playlist may contain thousands of channels. Do not create a
            // new thread for every configured source or parse caches on the UI.
            val pool=java.util.concurrent.Executors.newFixedThreadPool(minOf(3,sources.size))
            try{
                val tasks=sources.mapIndexed{index,p->
                    pool.submit {
                        try{
                            val file=cacheFile(p)
                            val metadataKey="playlist_http_"+playlistCacheKey(p)
                            val parsed:List<Channel>
                            if(p.url==licensedPlaylist){
                                val body=licensedM3u()
                                parsed=parseM3u(body,p.name)
                                if(parsed.isEmpty())throw IllegalStateException("No valid channels in "+p.name)
                                val temp=java.io.File(file.absolutePath+".tmp")
                                temp.writeText(body)
                                if(!temp.renameTo(file)){temp.copyTo(file,overwrite=true);temp.delete()}
                                refreshed[index]=true
                                checked[index]=true
                            }else if(p.url.startsWith("file:",true)){
                                val localFile=java.io.File(java.net.URI(p.url))
                                if(!localFile.isFile)throw java.io.IOException("Imported playlist file is missing")
                                val body=localFile.inputStream().use{readPlaylistText(it)}
                                parsed=parseM3u(body,p.name)
                                if(parsed.isEmpty())throw IllegalStateException("No valid channels in "+p.name)
                                val temp=java.io.File(file.absolutePath+".tmp")
                                temp.writeText(body)
                                if(!temp.renameTo(file)){temp.copyTo(file,overwrite=true);temp.delete()}
                                refreshed[index]=true
                                checked[index]=true
                            }else{
                                val connection=URL(p.url).openConnection() as java.net.HttpURLConnection
                                try{
                                    connection.connectTimeout=12000
                                    connection.readTimeout=15000
                                    connection.useCaches=false
                                    connection.instanceFollowRedirects=true
                                    connection.setRequestProperty("Cache-Control","no-cache")
                                    connection.setRequestProperty("User-Agent","HomeTV/1.0 AndroidTV")
                                    // A validator is useful only while its matching local body exists.
                                    // After Clear Cache, fetch a full response instead of accepting 304
                                    // with no playlist file available to parse.
                                    if(file.exists() && !forceRebuild){
                                        val oldTag=prefs.getString(metadataKey+"_etag",null)
                                        if(!oldTag.isNullOrBlank())connection.setRequestProperty("If-None-Match",oldTag)
                                        val oldModified=prefs.getLong(metadataKey+"_modified",0L)
                                        if(oldModified>0L)connection.setIfModifiedSince(oldModified)
                                    }
                                    val code=connection.responseCode
                                    checked[index]=true
                                    if(code==java.net.HttpURLConnection.HTTP_NOT_MODIFIED && file.exists()){
                                        parsed=parseM3u(readPlaylistCache(file),p.name)
                                    }else if(code in 200..299){
                                        val body=connection.inputStream.use{readPlaylistText(it)}
                                        val bodyHash=MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
                                            .joinToString(""){"%02x".format(it)}
                                        val oldHash=prefs.getString(metadataKey+"_hash",null)
                                        parsed=if(!forceRebuild && bodyHash==oldHash && file.exists()){
                                            parseM3u(readPlaylistCache(file),p.name)
                                        }else{
                                            val fresh=parseM3u(body,p.name)
                                            if(fresh.isEmpty())throw IllegalStateException("No valid channels in "+p.name)
                                            val temp=java.io.File(file.absolutePath+".tmp")
                                            temp.writeText(body)
                                            if(!temp.renameTo(file)){temp.copyTo(file,overwrite=true);temp.delete()}
                                            refreshed[index]=true
                                            fresh
                                        }
                                        prefs.edit()
                                            .putString(metadataKey+"_etag",connection.getHeaderField("ETag"))
                                            .putLong(metadataKey+"_modified",connection.lastModified)
                                            .putString(metadataKey+"_hash",bodyHash)
                                            .apply()
                                    }else{
                                        throw java.io.IOException("HTTP "+code+" while checking "+p.name)
                                    }
                                    if(parsed.isEmpty())throw IllegalStateException("No valid channels in "+p.name)
                                }finally{connection.disconnect()}
                            }
                            val discovered=parsed.filter{it.mediaType=="web" && it.url.contains("/channel/")}
                                .flatMap{discoverWebDirectory(it)}
                            results[index]=parsed+discovered
                        }catch(e:Exception){
                            HomeTvDiagnostics.w("HomeTV","Playlist rebuild failed: "+p.name,e)
                            if(!forceRebuild){
                                try{
                                    val file=cacheFile(p)
                                    if(file.exists())results[index]=parseM3u(readPlaylistCache(file),p.name)
                                }catch(e:Exception){HomeTvDiagnostics.w("HomeTV","Cache fallback failed",e)}
                            }
                        }
                    }
                }
                tasks.forEach{it.get()}
            }finally{pool.shutdownNow()}
            val updated=refreshed.count{it}
            val checkedCount=checked.count{it}
            val combined=mutableListOf<Channel>()
            if(activeOnly){
                // Refresh only the active playlist without dropping cached channels
                // from every other enabled playlist already loaded at startup.
                val refreshedSources=sources.map{it.name}.toSet()
                combined.addAll(previousChannels.filter{it.source !in refreshedSources})
            }
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
                Toast.makeText(this,"Playlist check complete: "+updated+" updated • "+
                    (checkedCount-updated).coerceAtLeast(0)+" unchanged • "+
                    (sources.size-checkedCount).coerceAtLeast(0)+" failed",Toast.LENGTH_LONG).show()
                if(updated==0 && nextChannels.isEmpty()){
                    Toast.makeText(this,"Refresh failed. Existing channels preserved.",Toast.LENGTH_LONG).show()
                }else{
                    channels=nextChannels
                    if(forceRebuild)Toast.makeText(this,"Rebuilt "+updated+"/"+sources.size+" playlists • "+actualChannelCount()+" channels",Toast.LENGTH_LONG).show()
                }
                // Preserve whichever channel is playing when the refresh finishes,
                // not whichever channel happened to be playing when the network
                // request began. The viewer may have changed channels meanwhile.
                if(active>=0){
                    val currentBeforeApply=previousChannels.getOrNull(active)
                    val currentUrl=currentBeforeApply?.url
                    val currentSource=currentBeforeApply?.source
                    val relocated=channels.indexOfFirst{it.url==currentUrl &&
                        (currentSource==null || it.source==currentSource)}
                    if(relocated>=0){
                        val indexChanged=relocated!=active
                        active=relocated
                        // Player listeners and delayed recovery callbacks are bound
                        // to the original channel index. Rebind playback if a refresh
                        // moved the current channel so those callbacks remain valid.
                        if(indexChanged && videoScreen!=null)playIndex(relocated)
                    }else{
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
        if(!licenseReady || safeStartupMode)return
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
        // Bound work per UI dispatch even when many entries share one URL.
        // The rotating offset resumes scanning on the next cycle.
        val scanBudget=minOf(total,1000)
        while(scanned<scanBudget && snapshot.size<250){
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
        val deletedGroups=prefs.getStringSet("deleted_groups",emptySet()) ?: emptySet()
        val parsed=M3uParser.parse(body,source,deletedGroups).toMutableList()
        addSavedWebTv(parsed)
        return parsed
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
        root.addView(button("Refresh channel groups"){refresh({showChannels()},true)})
        val groups=playlistIndicesIncludingLocked().map{channels[it].group}.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
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
        val source=selectedPlaylistSource() ?: channels.firstOrNull{it.group==group}?.source
        val open={openLockedGroup(group){showGroupContents(group)}}
        if(source!=null)openLockedPlaylist(source,open) else open()
    }
    private fun showGroupContents(group:String){
        prefs.edit().putBoolean("favorites_surfing",false).apply()
        reset(group)
        root.addView(button("Protect group"){toggleLock(group,true)})
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
        root.addView(button("All channel groups"){showChannels()})
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
    private fun lockedPlaylistNames():Set<String> =
        prefs.getStringSet("locked_playlists",emptySet()) ?: emptySet()
    private fun lockedGroupNames():Set<String> =
        prefs.getStringSet("locked_groups",emptySet()) ?: emptySet()

    private fun openLockedPlaylist(source:String,action:()->Unit){
        if(source in lockedPlaylistNames() && source !in unlockedPlaylistsThisSession){
            requirePin({unlockedPlaylistsThisSession.add(source);action()},
                "playlist:"+source){deletePlaylistAfterPinFailures(source)}
        }else action()
    }
    private fun openLockedGroup(group:String,action:()->Unit){
        if(group in lockedGroupNames() && group !in unlockedGroupsThisSession){
            requirePin({unlockedGroupsThisSession.add(group);action()},
                "group:"+group){deleteGroupAfterPinFailures(group)}
        }else action()
    }
    // Shared deletion path for Playlist Manager and the in-player playlist drawer.
    // Remove a source's channels from memory immediately, not only after refresh.
    private fun deletePlaylistSource(name:String){
        // Invalidate an in-flight network refresh so it cannot re-add this source.
        refreshGeneration++
        val items=playlists()
        val removedIndex=items.indexOfFirst{it.name==name}
        if(removedIndex<0)return
        val removed=items[removedIndex]
        val wasPlaying=channels.getOrNull(active)?.source==name
        if(wasPlaying)stopPlayback()
        savePlaylists(items.filterNot{it.name==name})
        if(items.none{it.name!=name && it.url==removed.url})
            try{cacheFile(removed).delete()}catch(_:Exception){}
        clearPlaylistLocalHistory(name)
        channels=channels.filter{it.source!=name}.toMutableList()
        channelIndexOwner=null
        channelIndexSize=-1
        invalidateSurfingCache()
        val selected=prefs.getString("selected_playlist_source",null)
        // Continue forward from the deleted playlist's former position, wrapping
        // once, and skip disabled/empty playlists. This preserves playlist order.
        val next=(1 until items.size).asSequence()
            .map{items[(removedIndex+it)%items.size]}
            .firstOrNull{it.enabled && channels.any{c->c.source==it.name}}?.name
        if(selected==name || wasPlaying){
            prefs.edit().apply{
                if(next==null)remove("selected_playlist_source") else putString("selected_playlist_source",next)
            }.apply()
        }
        if(wasPlaying){
            active=-1
            val last=next?.let{prefs.getString("last_url_"+it,null)}
            val target=channels.indexOfFirst{it.source==next && it.url==last}
            val fallback=channels.indexOfFirst{it.source==next && it.mediaType=="live"}
            if(target>=0)playIndex(target) else if(fallback>=0)playIndex(fallback) else showHome()
        }else{
            if(videoScreen!=null && active>=0)showOverlayList() else managePlaylists()
        }
    }
    private fun clearPlaylistLocalHistory(name:String){
        val groupNames=channels.filter{it.source==name}.map{it.group}.toSet()
        val groupsUsedElsewhere=channels.filter{it.source!=name && it.group in groupNames}.map{it.group}.toSet()
        val groupsToClear=groupNames-groupsUsedElsewhere
        val deleted=prefs.getStringSet("deleted_groups",emptySet())?.toMutableSet() ?: mutableSetOf()
        deleted.removeAll{it.startsWith(name+"|")}
        val playlistLocks=lockedPlaylistNames().toMutableSet()
        playlistLocks.remove(name)
        val groupLocks=lockedGroupNames().toMutableSet()
        groupLocks.removeAll(groupsToClear)
        val edit=prefs.edit()
            .putStringSet("deleted_groups",deleted)
            .putStringSet("locked_playlists",playlistLocks)
            .putStringSet("locked_groups",groupLocks)
            .remove("pin_failures_playlist:"+name)
            .remove("last_url_"+name)
        groupsToClear.forEach{edit.remove("pin_failures_group:"+it)}
        edit.apply()
        unlockedPlaylistsThisSession.remove(name)
        unlockedGroupsThisSession.removeAll(groupsToClear)
    }
    private fun deletePlaylistAfterPinFailures(name:String){
        // Invalidate an in-flight network refresh so it cannot re-add this source.
        refreshGeneration++
        val items=playlists().toMutableList()
        val removed=items.firstOrNull{it.name==name}
        if(removed==null)return
        val wasPlaying=channels.getOrNull(active)?.source==name
        if(wasPlaying)stopPlayback()
        savePlaylists(items.filterNot{it.name==name})
        if(items.none{it.name!=name && it.url==removed.url})
            try{cacheFile(removed).delete()}catch(_:Exception){}
        clearPlaylistLocalHistory(name)
        channels=channels.filter{it.source!=name}.toMutableList()
        channelIndexOwner=null;channelIndexSize=-1
        val selected=prefs.getString("selected_playlist_source",null)
        val next=items.firstOrNull{it.name!=name && it.enabled && channels.any{c->c.source==it.name}}?.name
        val edit=prefs.edit()
        if(selected==name){if(next==null)edit.remove("selected_playlist_source") else edit.putString("selected_playlist_source",next)}
        edit.apply()
        if(wasPlaying){
            active=-1
            val last=next?.let{prefs.getString("last_url_"+it,null)}
            val target=channels.indexOfFirst{it.source==next && it.url==last}
            val fallback=channels.indexOfFirst{it.source==next && it.mediaType=="live"}
            if(target>=0)playIndex(target) else if(fallback>=0)playIndex(fallback) else showHome()
        }else{
            Toast.makeText(this,"Playlist deleted after 3 incorrect PIN attempts",Toast.LENGTH_LONG).show()
            showPlaylistDrawer()
        }
    }
    private fun deleteGroupAfterPinFailures(group:String){
        val sources=channels.filter{it.group==group}.map{it.source}.toSet()
        val deleted=prefs.getStringSet("deleted_groups",emptySet())?.toMutableSet() ?: mutableSetOf()
        sources.forEach{deleted.add(it+"|"+group)}
        prefs.edit().putStringSet("deleted_groups",deleted)
            .remove("pin_failures_group:"+group).apply()
        val wasPlaying=channels.getOrNull(active)?.group==group
        if(wasPlaying)stopPlayback()
        channels=channels.filter{it.group!=group}.toMutableList()
        channelIndexOwner=null;channelIndexSize=-1
        val locks=lockedGroupNames().toMutableSet();locks.remove(group)
        prefs.edit().putStringSet("locked_groups",locks).apply()
        if(wasPlaying){
            active=-1
            val next=channels.indexOfFirst{it.mediaType=="live"}
            if(next>=0)playIndex(next) else showHome()
        }else{
            Toast.makeText(this,"Group deleted after 3 incorrect PIN attempts",Toast.LENGTH_LONG).show()
            if(active<0)showChannels()
        }
    }
    private fun toggleLock(value:String,group:Boolean){
        withPinSetup {
            val key=if(group)"locked_groups" else "locked_channels"
            val set=(prefs.getStringSet(key,emptySet())?:emptySet()).toMutableSet()
            if(!set.add(value))set.remove(value)
            prefs.edit().putStringSet(key,set).apply()
            invalidateSurfingCache()
            if(active>=0 && channels.getOrNull(active)?.let{isLocked(it)}==true)allChannels()
            else if(active>=0)showBanner("Channel lock updated")
            else allChannels()
        }
    }
    // First use of any lock action offers PIN creation instead of silently refusing.
    // Existing PINs continue to require authentication before lock changes.
    private fun withPinSetup(action:()->Unit){
        if(prefs.getString("pin",null)!=null){
            requirePin(action)
            return
        }
        val layout=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(24,12,24,4)
        }
        val first=EditText(this).apply{
            hint="Create PIN"
            inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine=true
        }
        val confirm=EditText(this).apply{
            hint="Confirm PIN"
            inputType=android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine=true
        }
        layout.addView(first)
        layout.addView(confirm)
        val dialog=AlertDialog.Builder(this)
            .setTitle("Create parental PIN")
            .setMessage("Set a PIN to protect locked playlists, groups and channels.")
            .setView(layout)
            .setPositiveButton("Save PIN",null)
            .setNegativeButton("Cancel",null)
            .create()
        dialog.setOnShowListener{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val value=first.text.toString()
                if(value.isBlank()){
                    first.error="Enter a PIN"
                    first.requestFocus()
                }else if(value!=confirm.text.toString()){
                    confirm.error="PINs do not match"
                    confirm.requestFocus()
                }else{
                    prefs.edit().putString("pin",hash(value)).apply()
                    dialog.dismiss()
                    Toast.makeText(this,"PIN saved",Toast.LENGTH_SHORT).show()
                    action()
                }
            }
        }
        showSideDialog(dialog)
        first.requestFocus()
    }
    private fun hash(pin:String)=MessageDigest.getInstance("SHA-256").digest(pin.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun requirePin(action:()->Unit){
        requirePin(action,null,null)
    }
    private fun requirePin(action:()->Unit,failureKey:String?,onThirdFailure:(()->Unit)?){
        val saved=prefs.getString("pin",null)
        if(saved==null){Toast.makeText(this,"Set a PIN in Settings first",Toast.LENGTH_LONG).show();return}
        val field=EditText(this).apply{inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD;hint="Password"}
        showSideDialog(AlertDialog.Builder(this).setTitle("Enter password").setView(field).setPositiveButton("Unlock"){_,_->
            if(hash(field.text.toString())==saved){
                if(failureKey!=null)prefs.edit().remove("pin_failures_"+failureKey).apply()
                action()
            }else if(failureKey!=null && onThirdFailure!=null){
                val key="pin_failures_"+failureKey
                val attempts=prefs.getInt(key,0)+1
                if(attempts>=3){
                    prefs.edit().remove(key).apply()
                    Toast.makeText(this,"3 incorrect PIN attempts. The locked "+failureKey.substringBefore(":")+" will be deleted.",Toast.LENGTH_LONG).show()
                    onThirdFailure()
                }else{
                    prefs.edit().putInt(key,attempts).apply()
                    Toast.makeText(this,"Incorrect PIN. "+(3-attempts)+" attempt(s) remaining before deletion.",Toast.LENGTH_LONG).show()
                }
            }else Toast.makeText(this,"Incorrect password",Toast.LENGTH_SHORT).show()
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
                // Preserve the dialog's native choice mode. Single-choice setting
                // dialogs need CHOICE_MODE_SINGLE for DPAD focus to track previews;
                // ordinary action lists remain in their default non-choice mode.
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
                isFocusable=true
                isFocusableInTouchMode=true
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }
    // Explicitly classify .mpd URLs, including .isml/.mpd and query strings.
    // HLS and ordinary streams keep their existing Media3 handling.
    private fun mediaItemForStream(url:String):MediaItem {
        val path=try{Uri.parse(url).path.orEmpty().lowercase(java.util.Locale.ROOT)}
            catch(_:Exception){url.substringBefore('?').lowercase(java.util.Locale.ROOT)}
        val mime=when(streamKind(url)){
            "dash"->androidx.media3.common.MimeTypes.APPLICATION_MPD
            "hls"->androidx.media3.common.MimeTypes.APPLICATION_M3U8
            "smooth"->androidx.media3.common.MimeTypes.APPLICATION_SS
            else->null
        }
        return if(mime!=null)MediaItem.Builder().setUri(url).setMimeType(mime).build()
        else MediaItem.fromUri(url)
    }
    private fun confirmExit(){
        val dialog=AlertDialog.Builder(this).setTitle("Exit HomeTV?")
            .setMessage("Stop playback and close HomeTV?")
            .setPositiveButton("Exit"){_,_->
                // Exit the task instead of returning to the Home menu.
                stopPlayback()
                finishAffinity()
            }
            .setNegativeButton("Cancel",null).create()
        showSideDialog(dialog)
        // Android TV: initial OK confirms Exit; Back dismisses the dialog.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.requestFocus()
    }
    private fun preferredAudioLanguage():String =
        prefs.getString("preferred_audio_language","ml")?.takeIf{it.isNotBlank()} ?: "ml"

    private fun normalizeAudioLanguage(value:String?):String?{
        val raw=value?.trim()?.lowercase(java.util.Locale.ROOT)
            ?.substringBefore('-')?.substringBefore('_') ?: return null
        return when(raw){
            "ml","mal"->"ml"
            "en","eng"->"en"
            "hi","hin"->"hi"
            "ta","tam"->"ta"
            "ar","ara"->"ar"
            "und","unknown",""->null
            else->raw
        }
    }
    private fun audioTrackPreferenceKey(c:Channel)="audio_track_"+channelKey(c)
    private fun audioTrackSignatureKey(c:Channel)="audio_track_signature_"+channelKey(c)
    private fun vlcAudioTrackPreferenceKey(c:Channel)="audio_track_vlc_"+channelKey(c)

    private fun audioFormatSignature(format:androidx.media3.common.Format):String =
        listOf(format.id.orEmpty(),normalizeAudioLanguage(format.language).orEmpty(),
            format.label.orEmpty(),format.sampleMimeType.orEmpty(),
            format.channelCount.toString(),format.roleFlags.toString()).joinToString("|")

    private fun applyAudioTrackPreference(c:Channel,exo:ExoPlayer){
        val savedLanguage=prefs.getString(audioTrackPreferenceKey(c),null)
        val savedSignature=prefs.getString(audioTrackSignatureKey(c),null)
        val wanted=normalizeAudioLanguage(savedLanguage)
            ?: if(savedLanguage==null && !prefs.getBoolean("audio_use_stream_default",false))
                normalizeAudioLanguage(preferredAudioLanguage()) else null
        if(wanted==null && savedSignature.isNullOrBlank())return
        val available=exo.currentTracks.groups.asSequence()
            .filter{it.type==C.TRACK_TYPE_AUDIO}
            .flatMap{group->(0 until group.length).asSequence()
                .filter{index->group.isTrackSupported(index)}
                .map{index->Triple(group,index,group.getTrackFormat(index))}}
            .toList()
        val matching=if(!savedSignature.isNullOrBlank())
            available.firstOrNull{(_,_,format)->audioFormatSignature(format)==savedSignature}
                ?: available.firstOrNull{(_,_,format)->normalizeAudioLanguage(format.language)==wanted}
        else available.firstOrNull{(_,_,format)->normalizeAudioLanguage(format.language)==wanted}
        if(matching!=null){
            val (group,index,_)=matching
            if(!group.isTrackSelected(index)){
                exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,listOf(index)))
                    .build()
            }
        }else if(savedLanguage==null && wanted!=null){
            exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(wanted)
                .build()
        }
    }

    private fun vlcTrackLanguage(name:String):String?{
        val value=name.lowercase(java.util.Locale.ROOT)
        return when{
            value.contains("malayalam") || Regex("\\bmal\\b").containsMatchIn(value)->"ml"
            value.contains("english") || Regex("\\beng?\\b").containsMatchIn(value)->"en"
            value.contains("hindi") || Regex("\\bhin\\b").containsMatchIn(value)->"hi"
            value.contains("tamil") || Regex("\\btam\\b").containsMatchIn(value)->"ta"
            value.contains("arabic") || Regex("\\bara\\b").containsMatchIn(value)->"ar"
            else->null
        }
    }

    private fun applyVlcAudioTrackPreference(c:Channel,vlc:VlcPlayer){
        val tracks=vlc.audioTracks ?: return
        if(tracks.isEmpty())return
        val savedName=prefs.getString(vlcAudioTrackPreferenceKey(c),null)
        val wanted=if(savedName.isNullOrBlank()){
            if(prefs.getBoolean("audio_use_stream_default",false))null
            else normalizeAudioLanguage(preferredAudioLanguage())
        }else null
        val match=if(!savedName.isNullOrBlank())
            tracks.firstOrNull{it.name?.toString()==savedName}
        else tracks.firstOrNull{track->wanted!=null && vlcTrackLanguage(track.name?.toString().orEmpty())==wanted}
        if(match!=null && vlc.audioTrack!=match.id){
            vlc.setAudioTrack(match.id)
        }
    }

    private fun vlcAudioTracks(vlc:VlcPlayer){
        val tracks=vlc.audioTracks?.filter{it.id>=0}.orEmpty()
        if(tracks.isEmpty()){
            AlertDialog.Builder(this).setTitle("Audio tracks")
                .setMessage("LibVLC has not reported any audio tracks yet. Wait for playback to start and try again.")
                .setPositiveButton("OK",null).show()
            return
        }
        val names=tracks.map{track->
            val name=track.name?.toString()?.takeIf{it.isNotBlank()} ?: "Audio track "+track.id
            name+(if(track.id==vlc.audioTrack)" ✓" else "")
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Select audio track")
            .setItems(names){_,position->
                val selected=tracks[position]
                if(vlc.setAudioTrack(selected.id)){
                    channels.getOrNull(active)?.let{prefs.edit()
                        .putString(vlcAudioTrackPreferenceKey(it),selected.name?.toString().orEmpty()).apply()}
                    showBanner("Audio track: "+names[position])
                }else showBanner("Could not change the LibVLC audio track",true)
            }
            .setNeutralButton("Use stream default next time"){_,_->
                channels.getOrNull(active)?.let{prefs.edit().remove(vlcAudioTrackPreferenceKey(it)).apply()}
                showBanner("Audio preference cleared for this channel")
            }
            .setNegativeButton("Cancel",null).show()
    }

    private fun choosePreferredAudioLanguage(){
        val choices=arrayOf("Malayalam (ml)","English (en)","Hindi (hi)","Tamil (ta)","Arabic (ar)","Use stream default")
        val codes=arrayOf("ml","en","hi","ta","ar","")
        val current=if(prefs.getBoolean("audio_use_stream_default",false)) "" else preferredAudioLanguage()
        val selected=codes.indexOf(current).let{if(it<0)0 else it}
        var pending=selected
        val dialog=AlertDialog.Builder(this).setTitle("Preferred audio language")
            .setSingleChoiceItems(choices,selected){_,which->pending=which}
            .setPositiveButton("OK"){dialog,_->
                val which=pending
                val code=codes[which]
                if(code.isBlank()){
                    prefs.edit().remove("preferred_audio_language").putBoolean("audio_use_stream_default",true).apply()
                }else{
                    prefs.edit().putString("preferred_audio_language",code).putBoolean("audio_use_stream_default",false).apply()
                }
                dialog.dismiss()
                val index=active
                if(index in channels.indices){
                    val channel=channels[index]
                    prefs.edit().remove(audioTrackPreferenceKey(channel))
                        .remove(audioTrackSignatureKey(channel))
                        .remove(vlcAudioTrackPreferenceKey(channel)).apply()
                    player?.let{exo->
                        exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon()
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .setPreferredAudioLanguage(if(code.isBlank())null else code)
                            .build()
                    }
                    vlcPlayer?.let{vlc->
                        if(code.isNotBlank())applyVlcAudioTrackPreference(channel,vlc)
                    }
                }
                showBanner(if(code.isBlank())"Audio: stream default" else "Preferred audio: "+choices[which])
            }.setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{
            val list=dialog.listView ?:return@setOnShowListener
            list.choiceMode=android.widget.ListView.CHOICE_MODE_SINGLE
            list.setItemChecked(selected,true);list.setSelection(selected)
            list.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){pending=position;list.setItemChecked(position,true)}
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            }
        }
        dialog.show()
    }

    private fun audioTracks(){
        val vlc=vlcPlayer
        if(vlc!=null){vlcAudioTracks(vlc);return}
        val exo=player
        if(exo==null){Toast.makeText(this,"Audio track selection is available for IPTV streams",Toast.LENGTH_LONG).show();return}
        val entries=mutableListOf<Triple<Tracks.Group,Int,androidx.media3.common.Format>>()
        val names=mutableListOf<String>()
        for(group in exo.currentTracks.groups){
            if(group.type!=C.TRACK_TYPE_AUDIO)continue
            for(i in 0 until group.length){
                val fmt=group.getTrackFormat(i)
                val language=normalizeAudioLanguage(fmt.language) ?: fmt.language ?: "Unknown language"
                val codec=fmt.sampleMimeType ?: "Unknown codec"
                val supported=group.isTrackSupported(i)
                names.add(language+" · "+codec+(if(group.isTrackSelected(i))" ✓" else "")+(if(!supported)" (unsupported)" else ""))
                if(supported)entries.add(Triple(group,i,fmt))
            }
        }
        val supportedNames=entries.map{entry->
            val group=entry.first;val index=entry.second
            val fmt=entry.third
            val language=normalizeAudioLanguage(fmt.language) ?: fmt.language ?: "Unknown language"
            language+" · "+(fmt.sampleMimeType ?: "Unknown codec")+
                (if(group.isTrackSelected(index))" ✓" else "")
        }
        if(entries.isEmpty()){
            AlertDialog.Builder(this).setTitle("Audio tracks")
                .setMessage(if(names.isEmpty())"No audio track was detected for this channel. Try another stream URL."
                    else "Audio tracks were detected, but this device cannot currently decode them.")
                .setPositiveButton("OK",null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Select audio track").setItems(supportedNames.toTypedArray()){_,position->
            val (group,index,format)=entries[position]
            exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,listOf(index)))
                .build()
            channels.getOrNull(active)?.let{channel->
                val language=normalizeAudioLanguage(format.language)
                val edit=prefs.edit().putString(audioTrackSignatureKey(channel),audioFormatSignature(format))
                if(language!=null)edit.putString(audioTrackPreferenceKey(channel),language)
                else edit.remove(audioTrackPreferenceKey(channel))
                edit.remove(vlcAudioTrackPreferenceKey(channel)).apply()
            }
            showBanner("Audio track: "+supportedNames[position])
        }.setNeutralButton("Automatic"){_,_->
            channels.getOrNull(active)?.let{channel->prefs.edit()
                .remove(audioTrackPreferenceKey(channel)).remove(audioTrackSignatureKey(channel)).apply()}
            exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                .setPreferredAudioLanguage(if(prefs.getBoolean("audio_use_stream_default",false)) null else preferredAudioLanguage())
                .build()
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

    private data class PicturePreset(val brightness:Float,val contrast:Float,val saturation:Float)
    private val picturePresets=linkedMapOf(
        "Standard" to PicturePreset(1.00f,1.00f,1.00f),
        "Cinema" to PicturePreset(0.98f,1.08f,0.90f),
        "Vivid" to PicturePreset(1.08f,1.15f,1.25f),
        "Natural" to PicturePreset(1.00f,1.00f,0.95f),
        "Soft" to PicturePreset(0.92f,0.92f,0.90f)
    )
    private fun picturePresetKey(c:Channel)="picture_mode_"+hash(c.url)
    private fun pictureCustomKey(c:Channel,field:String)="picture_custom_"+field+"_"+hash(c.url)
    private var previewPictureChannelKey:String?=null
    private var previewPictureMode:String?=null
    private fun selectedPicturePreset(c:Channel)=
        if(previewPictureChannelKey==channelKey(c)) previewPictureMode ?: (prefs.getString(picturePresetKey(c),"Standard") ?: "Standard")
        else prefs.getString(picturePresetKey(c),"Standard")?.takeIf{it=="Custom" || picturePresets.containsKey(it)} ?: "Standard"
    private fun selectedPictureValues(c:Channel):PicturePreset{
        if(selectedPicturePreset(c)=="Custom"){
            return PicturePreset(
                (prefs.getInt(pictureCustomKey(c,"brightness"),50)-50).coerceIn(-50,50)/50f+1f,
                (prefs.getInt(pictureCustomKey(c,"contrast"),50)-50).coerceIn(-50,50)/50f+1f,
                (prefs.getInt(pictureCustomKey(c,"saturation"),50)-50).coerceIn(-50,50)/50f+1f
            )
        }
        return picturePresets[selectedPicturePreset(c)] ?: picturePresets.getValue("Standard")
    }
    @UnstableApi
    private fun media3PictureEffects(c:Channel):List<androidx.media3.common.Effect>{
        val preset=selectedPictureValues(c)
        val brightness=((preset.brightness-1f)*0.5f).coerceIn(-1f,1f)
        val contrast=((preset.contrast-1f)*0.5f).coerceIn(-1f,1f)
        val saturation=((preset.saturation-1f)*100f).coerceIn(-100f,100f)
        return buildList {
            if(brightness!=0f)add(Brightness(brightness))
            if(contrast!=0f)add(Contrast(contrast))
            if(saturation!=0f)add(HslAdjustment.Builder().adjustSaturation(saturation).build())
        }
    }
    @UnstableApi
    private fun applyMedia3PicturePreset(exo:ExoPlayer,c:Channel){
        // Standard means native Media3 rendering. Preset changes restart the
        // channel so this is decided before prepare(), not on a live TV decoder.
        if(selectedPicturePreset(c)=="Standard")return
        try{
            exo.setVideoEffects(media3PictureEffects(c))
        }catch(e:Exception){
            // Keep the decoder's native surface path on adjustment failure.
            HomeTvDiagnostics.w("HomeTV","Media3 picture adjustment unavailable; native video rendering retained",e)
        }
    }
    private var picturePreviewRestartTask:Runnable?=null
    private fun restartCurrentChannelForPicturePreset(index:Int,c:Channel){
        if(index !in channels.indices || active!=index || videoScreen==null ||
            channelKey(channels[index])!=channelKey(c))return
        picturePreviewRestartTask?.let{handler.removeCallbacks(it)}
        val generation=playbackGeneration
        // Recreate the decoder with the selected preset before prepare(). This
        // avoids changing a live GL/filter pipeline while a TV decoder is active.
        val task=Runnable{
            if(active==index && playbackGeneration==generation && videoScreen!=null &&
                channels.getOrNull(index)?.let{channelKey(it)==channelKey(c)}==true){
                picturePreviewRestartTask=null
                playIndex(index)
            }
        }
        picturePreviewRestartTask=task
        handler.postDelayed(task,650L)
    }
    private fun previewPictureChoice(index:Int,c:Channel,mode:String){
        previewPictureChannelKey=channelKey(c)
        previewPictureMode=mode
        if(active==index)restartCurrentChannelForPicturePreset(index,c)
    }
    private fun clearPicturePreview(index:Int,c:Channel){
        if(previewPictureChannelKey==channelKey(c)){
            previewPictureChannelKey=null
            previewPictureMode=null
            if(active==index)restartCurrentChannelForPicturePreset(index,c)
        }
    }
    private fun applyPictureChoice(index:Int,c:Channel,mode:String){
        clearPicturePreview(index,c)
        prefs.edit().putString(picturePresetKey(c),mode)
            .remove(pictureCustomKey(c,"brightness")).remove(pictureCustomKey(c,"contrast"))
            .remove(pictureCustomKey(c,"saturation")).apply()
        if(active==index)restartCurrentChannelForPicturePreset(index,c)
        showBanner(c.name+" picture mode: "+mode)
    }
    private fun configureManualPicture(activeIndex:Int,c:Channel){
        val current=selectedPictureValues(c)
        fun toSlider(value:Float)=((value-1f)*50f+50f).toInt().coerceIn(0,100)
        val brightness=android.widget.SeekBar(this).apply{max=100;progress=toSlider(current.brightness)}
        val contrast=android.widget.SeekBar(this).apply{max=100;progress=toSlider(current.contrast)}
        val saturation=android.widget.SeekBar(this).apply{max=100;progress=toSlider(current.saturation)}
        val layout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(28,16,28,12)}
        fun addControl(name:String,bar:android.widget.SeekBar){
            val caption=TextView(this).apply{textSize=17f;setTextColor(android.graphics.Color.WHITE);text=name+": "+bar.progress+"%"}
            bar.setOnSeekBarChangeListener(object:android.widget.SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(seekBar:android.widget.SeekBar?,value:Int,fromUser:Boolean){caption.text=name+": "+value+"%"}
                override fun onStartTrackingTouch(seekBar:android.widget.SeekBar?){}
                override fun onStopTrackingTouch(seekBar:android.widget.SeekBar?){}
            })
            layout.addView(caption);layout.addView(bar)
        }
        addControl("Brightness",brightness);addControl("Contrast",contrast);addControl("Saturation",saturation)
        val dialog=AlertDialog.Builder(this).setTitle(c.name+" • Manual picture settings").setView(layout)
            .setPositiveButton("Apply"){_,_->
                prefs.edit().putString(picturePresetKey(c),"Custom")
                    .putInt(pictureCustomKey(c,"brightness"),brightness.progress)
                    .putInt(pictureCustomKey(c,"contrast"),contrast.progress)
                    .putInt(pictureCustomKey(c,"saturation"),saturation.progress).apply()
                if(active==activeIndex)restartCurrentChannelForPicturePreset(activeIndex,c)
                showBanner(c.name+" custom picture settings saved")
            }.setNeutralButton("Reset"){_,_->applyPictureChoice(activeIndex,c,"Standard")}
            .setNegativeButton("Cancel",null).create()
        showSideDialog(dialog)
    }
    private fun configureChannelPicture(activeIndex:Int){
        val c=channels.getOrNull(activeIndex)?:return
        val modes=picturePresets.keys.toList()
        val names=modes.map{if(it=="Standard")"Engine Native (Original)" else it}.toTypedArray()
        val selected=selectedPicturePreset(c)
        var pending=modes.indexOf(selected).coerceAtLeast(0)
        val dialog=AlertDialog.Builder(this).setTitle(c.name+" • Picture mode")
            .setMessage("Presets are HomeTV-defined starting points, not TV calibration standards, and affect only this channel. Engine Native (Original) adds no HomeTV correction. Other presets preview while browsing. OK saves; BACK cancels.")
            .setSingleChoiceItems(names,pending){_,which->
                pending=which
                previewPictureChoice(activeIndex,c,modes[which])
            }.setPositiveButton("OK",null)
            .setNeutralButton("Manual adjustments",null)
            .setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{
            val list=dialog.listView ?:return@setOnShowListener
            list.choiceMode=android.widget.ListView.CHOICE_MODE_SINGLE
            list.setItemChecked(pending,true);list.setSelection(pending)
            list.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){
                    pending=position
                    list.setItemChecked(position,true)
                    previewPictureChoice(activeIndex,c,modes[position])
                }
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                applyPictureChoice(activeIndex,c,modes[pending]);dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener{
                clearPicturePreview(activeIndex,c);dialog.dismiss();configureManualPicture(activeIndex,c)
            }
        }
        dialog.setOnDismissListener{clearPicturePreview(activeIndex,c)}
        showSideDialog(dialog)
    }

    private fun showChannelOptions(){
        if(active !in channels.indices)return
        val c=channels[active]
        val opts=arrayOf(
            if(channels.any{sameChannel(it,c) && favoriteIds.contains(id(it))})"Remove favorite" else "Add favorite",
            if(isManuallyHidden(c))"Unhide channel" else "Hide channel",
            if(isLocked(c))"Unlock channel" else "Lock channel",
            "Alternative Source ("+(alternateIndices(active).size)+" streams)",
            "Aspect Ratio ("+(prefs.getString("screen_channel_"+hash(c.url),null) ?: prefs.getString("screen_mode","Fit"))+")",
            "Picture Quality ("+(prefs.getString("quality_channel_"+hash(c.url),null) ?: prefs.getString("quality_global","Auto"))+")",
            "Audio tracks",
            "Subtitles",
            "Sleep timer",
            "Playback Engine ("+(prefs.getString("manual_engine_"+channelKey(c),null) ?: prefs.getString("engine","Media3"))+")",
            "Channel list",
            "Channel volume ("+prefs.getInt("channel_volume_"+hash(c.url),100)+"%)",
            "Buffering ("+(prefs.getString("buffer_channel_"+channelKey(c),null) ?: "Universal")+")",
            "Picture adjustments"
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
                    11->configureChannelVolume(active)
                    12->chooseChannelBufferProfile(active)
                    13->configureChannelPicture(active)
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
        if(!prefs.getBoolean("clock_enabled",true) ||
            (!prefs.getBoolean("clock_show_date",true) &&
             !prefs.getBoolean("clock_show_time",true) &&
             !prefs.getBoolean("clock_show_day",true)))return
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
            alpha=1f
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
        var pageIndex=0
        fun render(animate:Boolean){
            val now=java.util.Date()
            val values=mutableListOf<String>()
            if(prefs.getBoolean("clock_show_date",true))
                values.add(java.text.SimpleDateFormat("dd MMM yyyy",java.util.Locale.getDefault()).format(now))
            if(prefs.getBoolean("clock_show_time",true)){
                val pattern=if(prefs.getBoolean("clock_24h",false))"HH:mm:ss" else "hh:mm:ss a"
                values.add(java.text.SimpleDateFormat(pattern,java.util.Locale.getDefault()).format(now))
            }
            if(prefs.getBoolean("clock_show_day",true))
                values.add(java.text.SimpleDateFormat("EEEE",java.util.Locale.getDefault()).format(now))
            if(values.isEmpty())return
            val rotation=HomeTvPolicies.nextClockValue(values,pageIndex) ?: return
            val value=rotation.first
            pageIndex=rotation.second
            if(!animate || values.size==1){
                clock.text=value
                clock.alpha=1f
                clock.translationY=0f
            }else{
                clock.animate().cancel()
                clock.animate().alpha(0f).translationY(7f).setDuration(220L).withEndAction{
                    if(videoScreen===screen && clock.parent===screen){
                        clock.text=value
                        clock.translationY=(-7f)
                        clock.animate().alpha(1f).translationY(0f).setDuration(260L).start()
                    }
                }.start()
            }
        }
        render(false)
        val task=object:Runnable{
            override fun run(){
                if(videoScreen!==screen || clock.parent!==screen)return
                render(true)
                handler.postDelayed(this,4000L)
            }
        }
        clockTask=task
        handler.postDelayed(task,4000L)
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
        val delays=longArrayOf(600L,1400L,2700L,4500L,7000L,11000L,15000L)
        youtubeUnmuteTask?.let{handler.removeCallbacks(it)}
        var attempt=0
        val retry=object:Runnable{
            override fun run(){
                if(web!==viewer || active!=channelIndex || videoScreen!==screen ||
                    playbackGeneration!=generation)return
                viewer.evaluateJavascript("""(function(){
                    var v=document.querySelector('video');
                    if(!v)return 'waiting';
                    // A muted YouTube player can be paused during its initial
                    // buffering/advert transition; prepare audio even then.
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
            "Date · time · day overlay: "+if(prefs.getBoolean("clock_enabled",true))"ON" else "OFF",
            "Show date: "+if(prefs.getBoolean("clock_show_date",true))"ON" else "OFF",
            "Show time: "+if(prefs.getBoolean("clock_show_time",true))"ON" else "OFF",
            "Show day: "+if(prefs.getBoolean("clock_show_day",true))"ON" else "OFF",
            "Time format: "+if(prefs.getBoolean("clock_24h",false))"24-hour" else "12-hour",
            "Text size: "+prefs.getInt("clock_size",18)+" sp",
            "Position: "+(prefs.getString("clock_position","Top right") ?: "Top right")
        )
        val clockDialog=AlertDialog.Builder(this).setTitle("Date · time · day overlay")
            .setItems(choices){_,which->
                when(which){
                    0->{prefs.edit().putBoolean("clock_enabled",!prefs.getBoolean("clock_enabled",true)).apply();updateClock();clockSettings()}
                    1->{prefs.edit().putBoolean("clock_show_date",!prefs.getBoolean("clock_show_date",true)).apply();updateClock();clockSettings()}
                    2->{prefs.edit().putBoolean("clock_show_time",!prefs.getBoolean("clock_show_time",true)).apply();updateClock();clockSettings()}
                    3->{prefs.edit().putBoolean("clock_show_day",!prefs.getBoolean("clock_show_day",true)).apply();updateClock();clockSettings()}
                    4->{prefs.edit().putBoolean("clock_24h",!prefs.getBoolean("clock_24h",false)).apply();updateClock();clockSettings()}
                    5->{
                        val sizes=intArrayOf(12,14,16,18,20,24,28,32,36)
                        AlertDialog.Builder(this).setTitle("Clock text size")
                            .setSingleChoiceItems(sizes.map{ it.toString()+" sp" }.toTypedArray(),sizes.indexOf(prefs.getInt("clock_size",18)).coerceAtLeast(0)){dialog,i->
                                prefs.edit().putInt("clock_size",sizes[i]).apply();dialog.dismiss();updateClock();clockSettings()
                            }.setNegativeButton("Cancel",null).show()
                    }
                    6->{
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
        val channel=typed?.let{number->numericChannelIndices().getOrNull(number-1)}?.let{channels.getOrNull(it)}
        view.text=if(channel!=null) channelDigits+". "+channel.name else "Channel "+channelDigits
        view.bringToFront()
    }
    // Numeric tuning is scoped to the selected playlist, or the currently playing
    // playlist when All Playlists is selected. Avoid ambiguous duplicate numbers.
    private fun numericChannelIndices():List<Int>{
        val source=selectedPlaylistSource() ?:channels.getOrNull(active)?.source
        return if(source==null)channelNumbers().filter{channels[it].source==playlists().firstOrNull{p->p.enabled}?.name}
            else channelNumbers().filter{channels[it].source==source}
    }
    private fun enterChannelDigit(digit:Int){
        channelDigitTask?.let{handler.removeCallbacks(it)}
        channelDigits=(channelDigits+digit).takeLast(5)
        val typed=channelDigits.toIntOrNull()
        // Lightweight number display; do not rebuild EPG or video while typing.
        showNumericOverlay()
        if(typed==null || typed !in 1..numericChannelIndices().size)
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
        if(number in 1..numericChannelIndices().size)playIndex(preferredChannel(numericChannelIndices()[number-1]))
        else showBanner("Channel "+number+" is not available",true)
    }
    private var previewIndex=-1
    private var previewTask:Runnable?=null
    private var previewInfoPanel:View?=null
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
                exo.setMediaItem(mediaItemForStream(c.url))
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
                HomeTvDiagnostics.w("HomeTV","Preview unavailable",e)
                closeVideoPreview()
            }
        }
        previewVideoTask=task
        handler.postDelayed(task,900L)
    }
    private var lastPreviewNavigationAt=0L
    private fun previewChannel(delta:Int){
        // Limit rapid remote repeats on large lists to avoid UI/decoder overload.
        val now=android.os.SystemClock.uptimeMillis()
        if(now-lastPreviewNavigationAt<130L)return
        lastPreviewNavigationAt=now
        surfingDirection=if(delta>=0)1 else -1
        if(channels.isEmpty())return
        val available=surfingIndices()
        if(available.isEmpty())return
        previewTask?.let{handler.removeCallbacks(it)}
        previewTask=null
        val current=if(previewIndex>=0)previewIndex else active
        val next=nextSurfingIndex(current,delta,available)
        if(next !in channels.indices)return
        previewIndex=next
        // UP/DOWN only moves the highlighted channel. The current stream and
        // decoder remain untouched until OK is pressed.
        showChannelInfo(previewIndex,"Press OK to play")
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
        if(previewInfoPanel===banner)previewInfoPanel=null
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
                if(extra.isNotBlank())append("\n").append(extra)
            }
            textSize=18f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(18,4,8,4)
        })
        banner=panel
        val isPreview=previewIndex==index && index!=active
        previewInfoPanel=if(isPreview)panel else null
        screen.addView(panel,FrameLayout.LayoutParams((resources.displayMetrics.widthPixels*0.62f).toInt(),-2,Gravity.BOTTOM or Gravity.LEFT))
        // A preview stays visible until OK, BACK, another channel preview, or
        // immediate CH+/CH- tuning. Ordinary channel information still fades.
        val task=Runnable{
            if(banner===panel && previewInfoPanel!==panel){
                screen.removeView(panel)
                banner=null
            }
        }
        bannerTask=task
        if(!isPreview)handler.postDelayed(task,4000)
    }
    private fun clearChannelPreview(){
        val hadPreview=previewIndex>=0
        if(!hadPreview && previewPlayer==null && previewPanel==null)return
        previewIndex=-1
        previewTask?.let{handler.removeCallbacks(it)}
        previewTask=null
        closeVideoPreview()
        if(hadPreview){
            previewInfoPanel?.let{panel->
                (panel.parent as? android.view.ViewGroup)?.removeView(panel)
                if(banner===panel)banner=null
            }
            previewInfoPanel=null
            bannerTask?.let{handler.removeCallbacks(it)}
            bannerTask=null
        }
    }
    private fun confirmPreview(){
        val target=previewIndex
        clearChannelPreview()
        if(target in channels.indices)playIndex(preferredChannel(target))
        else showBanner("Use ▲ / ▼ to preview a channel, then press OK to play")
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
        val groups=playlistIndicesIncludingLocked().map{channels[it].group}.distinct()
        // Prefix every remote-selectable navigation item with a stable, visible number.
        // Group entries continue the numbering after the fixed navigation actions.
        val fixedOptions=listOf("⚙  Settings","⌂  Home","★  Favorites","🙈  Hidden channels","✓  Check hidden channels","▤  EPG / TV Guide","↻  Refresh from source","☰  All channels","▦  Playlists")
        val options=(fixedOptions+groups.map{"▸  "+it}).mapIndexed{index,label->
            "%02d".format(java.util.Locale.ROOT,index+1)+"   "+label
        }
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
        val names=(listOf("＋  Add Playlist","⚙  Manage Playlists","☰  All Playlists")+sources.map{"▸  "+it})
            .mapIndexed{index,label->"%02d".format(java.util.Locale.ROOT,index+1)+"   "+label}
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
            if(position==0){close();editPlaylist(null,true);return@setOnItemClickListener}
            if(position==1){close();managePlaylists();return@setOnItemClickListener}
            val selected=if(position==2)null else sources.getOrNull(position-3)
            close()
            val open={
                prefs.edit().apply{
                    if(selected==null)remove("selected_playlist_source")
                    else putString("selected_playlist_source",selected)
                }.apply()
                showOverlayList()
            }
            if(selected!=null)openLockedPlaylist(selected,open) else open()
        }
        list.setOnItemLongClickListener{_,_,position,_->
            val source=sources.getOrNull(position-3)
            if(source==null)return@setOnItemLongClickListener false
            val entry=playlists().indexOfFirst{it.name==source}
            val choices=arrayOf("Edit playlist","Disable playlist","Delete playlist","Manage playlists")
            AlertDialog.Builder(this).setTitle(source).setItems(choices){_,action->
                when(action){
                    0->if(entry>=0)editPlaylist(entry)
                    1->{val items=playlists();if(entry>=0){items[entry]=items[entry].copy(enabled=false);savePlaylists(items);Toast.makeText(this,"Playlist disabled",Toast.LENGTH_SHORT).show()};close();showOverlayList()}
                    2->if(entry>=0)AlertDialog.Builder(this).setTitle("Delete "+source+"?").setMessage("Remove this playlist and its channels from HomeTV?").setPositiveButton("Delete"){_,_->close();deletePlaylistSource(source)}.setNegativeButton("Cancel",null).show()
                    3->{close();managePlaylists()}
                }
            }.show()
            true
        }
        list.setOnKeyListener{_,code,event->
            if(event.action==KeyEvent.ACTION_DOWN &&
                (code==KeyEvent.KEYCODE_BACK || code==KeyEvent.KEYCODE_DPAD_RIGHT)){
                close();showLeftNavigation();true
            }else false
        }
        val selected=selectedPlaylistSource()
        list.setSelection(if(selected==null)2 else (sources.indexOf(selected)+3).coerceAtLeast(2))
        list.requestFocus()
    }
    private fun showGroupDrawer(group:String){
        val source=selectedPlaylistSource() ?: channels.firstOrNull{it.group==group}?.source
        val open={openLockedGroup(group){showGroupDrawerContents(group)}}
        if(source!=null)openLockedPlaylist(source,open) else open()
    }
    private fun showGroupDrawerContents(group:String){
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
        // Virtualize channel labels: do not allocate one string/view per channel.
        list.adapter=object:android.widget.BaseAdapter(){
            override fun getCount()=indices.size
            override fun getItem(position:Int):Any=channels[indices[position]]
            override fun getItemId(position:Int)=indices[position].toLong()
            override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View{
                val row=(convertView as? TextView) ?: TextView(this@MainActivity).apply{
                    textSize=18f
                    setTextColor(android.graphics.Color.WHITE)
                    setPadding(16,16,12,16)
                    isSingleLine=true
                    ellipsize=android.text.TextUtils.TruncateAt.END
                }
                row.text=channelLabel(indices[position])
                return row
            }
        }
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
    // Per-channel gain is separate from Android/TV master volume. 100% is
    // the original stream level; 200% can boost quiet PCM streams.
    private fun channelVolumeKey(c:Channel)="channel_volume_"+hash(channelKey(c))
    private fun channelVolumeGain(c:Channel):Float =
        prefs.getInt(channelVolumeKey(c),20).coerceIn(0,200)/100f
    private fun applyChannelVolume(){
        val c=channels.getOrNull(active) ?:return
        val gain=channelVolumeGain(c)
        activeChannelGainProcessor?.gain=gain
        player?.volume=1f
        try{vlcPlayer?.volume=(gain*100).toInt().coerceIn(0,200)}catch(_:Exception){}
        val webGain=gain.coerceAtMost(1f)
        web?.evaluateJavascript("try{var v=document.querySelector('video');if(v)v.volume="+webGain+";}catch(e){}",null)
    }
    private fun configureChannelVolume(index:Int){
        val context:Context=this
        val c=channels.getOrNull(index) ?:return
        val key=channelVolumeKey(c)
        val level=SeekBar(context).apply{
            max=200
            progress=prefs.getInt(key,20).coerceIn(0,200)
        }
        val label=TextView(context).apply{text="Channel volume: "+level.progress+"%";textSize=20f}
        val layout=LinearLayout(context).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(30,20,30,20)
            addView(label)
            addView(level)
            addView(TextView(context).apply{
                text="20% is the quiet default. 100% is the stream's original level; up to 200% can boost quiet channels. TV/soundbar master volume remains separate."
                textSize=14f
            })
        }
        level.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean){
                label.text="Channel volume: "+value+"%"
                if(fromUser && active==index){
                    val gain=value.coerceIn(0,200)/100f
                    activeChannelGainProcessor?.gain=gain
                    player?.volume=1f
                    try{vlcPlayer?.volume=value.coerceIn(0,200)}catch(_:Exception){}
                    web?.evaluateJavascript("try{var v=document.querySelector('video');if(v)v.volume="+gain.coerceAtMost(1f)+";}catch(e){}",null)
                }
            }
            override fun onStartTrackingTouch(bar:SeekBar?){}
            override fun onStopTrackingTouch(bar:SeekBar?){}
        })
        AlertDialog.Builder(context).setTitle(c.name+" channel volume").setView(layout)
            .setPositiveButton("Save"){_,_->
                prefs.edit().putInt(key,level.progress).apply()
                if(active==index)applyChannelVolume()
            }.setNeutralButton("Reset"){_,_->
                prefs.edit().remove(key).apply()
                if(active==index)applyChannelVolume()
            }.setNegativeButton("Cancel"){_,_->if(active==index)applyChannelVolume()}.show()
    }
    private fun showVolumeControl(){
        val audio=getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        if(audio.isVolumeFixed){
            AlertDialog.Builder(this).setTitle("Volume control")
                .setMessage("This device uses fixed audio output. Adjust volume using your TV or soundbar remote.")
                .setPositiveButton("OK",null).show()
            return
        }
        val stream=android.media.AudioManager.STREAM_MUSIC
        val max=audio.getStreamMaxVolume(stream).coerceAtLeast(1)
        val container=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(28,18,28,10)
        }
        val level=TextView(this).apply{
            textSize=20f
            gravity=Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
        }
        val slider=SeekBar(this).apply{
            this.max=max
            progress=audio.getStreamVolume(stream)
        }
        val buttons=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER
        }
        val down=Button(this).apply{text="−"}
        val mute=Button(this).apply{text="Mute"}
        val up=Button(this).apply{text="+"}
        buttons.addView(down,LinearLayout.LayoutParams(0,-2,1f))
        buttons.addView(mute,LinearLayout.LayoutParams(0,-2,2f))
        buttons.addView(up,LinearLayout.LayoutParams(0,-2,1f))
        container.addView(level)
        container.addView(slider)
        container.addView(buttons)
        fun refresh(){
            val current=audio.getStreamVolume(stream)
            val muted=audio.isStreamMute(stream) || current==0
            slider.progress=current
            level.text="Volume: "+(current*100/max)+"%"
            mute.text=if(muted)"Unmute" else "Mute"
            showHomeTvVolume()
        }
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seekBar:SeekBar?,progress:Int,fromUser:Boolean){
                if(fromUser){
                    audio.setStreamVolume(stream,progress,0)
                    refresh()
                }
            }
            override fun onStartTrackingTouch(seekBar:SeekBar?){}
            override fun onStopTrackingTouch(seekBar:SeekBar?){}
        })
        down.setOnClickListener{
            audio.adjustStreamVolume(stream,android.media.AudioManager.ADJUST_LOWER,0)
            refresh()
        }
        up.setOnClickListener{
            audio.adjustStreamVolume(stream,android.media.AudioManager.ADJUST_RAISE,0)
            refresh()
        }
        mute.setOnClickListener{
            audio.adjustStreamVolume(stream,
                if(audio.isStreamMute(stream) || audio.getStreamVolume(stream)==0)
                    android.media.AudioManager.ADJUST_UNMUTE
                else android.media.AudioManager.ADJUST_MUTE,0)
            refresh()
        }
        val dialog=AlertDialog.Builder(this).setTitle("TV Volume Control")
            .setView(container).setPositiveButton("Close",null).create()
        // The native dialog is intentionally user-dismissible; Back/Close
        // restores unobstructed playback without restarting the stream.
        dialog.setOnShowListener{refresh();slider.requestFocus()}
        dialog.show()
    }

    private fun showPlaybackSettings(){
        val screen=videoScreen ?:return
        screen.findViewWithTag<View>("settings_drawer")?.let{screen.removeView(it);return}
        screen.findViewWithTag<View>("channel_drawer")?.let{screen.removeView(it)}
        val options=arrayOf(
            "Default screen fit: "+(prefs.getString("screen_mode","Fit") ?: "Fit"),
            "Channel screen fit: "+screenMode(),
            "Stream quality","Buffering profile","Audio track","Preferred audio language","Subtitle settings","Sleep timer","Playback engine",
            "Clock display","Select stream source","Home","Live TV settings","Channel volume",
            "Picture adjustments ("+(channels.getOrNull(active)?.let{selectedPicturePreset(it)} ?: "Standard")+")"
        )
        val drawer=LinearLayout(this).apply{
            tag="settings_drawer"
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(0xF015283C.toInt())
            isFocusableInTouchMode=true
        }
        drawer.addView(TextView(this).apply{
            text="PLAYBACK OPTIONS"
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
            // Keep the playback-settings drawer underneath option dialogs. This
            // lets users make several changes without reopening the drawer.
            // Only destinations that replace the playback screen close it first.
            if(choice==11 || choice==12)close()
            when(choice){
                0->selectScreenMode(null)
                1->selectScreenMode(active.takeIf{it>=0})
                2->selectQuality(active.takeIf{it>=0})
                3->chooseBufferProfile()
                4->audioTracks()
                5->choosePreferredAudioLanguage()
                6->subtitleTracks()
                7->sleepTimer()
                8->if(active>=0)chooseChannelEngine(active)
                9->clockSettings()
                10->selectAlternative(active)
                11->showHome()
                12->liveSettings()
                13->if(active>=0)configureChannelVolume(active)
                14->if(active>=0)configureChannelPicture(active)
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
        // Auto must retain the broadcaster's full available quality range from
        // the first frame. Do not start at 480p and upgrade later: that silently
        // reduces the original stream quality during startup.
        qualityUpgradeTask?.let{handler.removeCallbacks(it)}
        qualityUpgradeTask=null
        applyQuality()
    }
    private var previewQualityMode:String?=null
    private var previewQualityIndex:Int?=null
    private fun applyQuality(){
        val exo=player ?:return
        val mode=if(previewQualityIndex==active)previewQualityMode ?: qualityMode() else qualityMode()
        applyQualityMode(exo,mode)
    }
    private fun applyQualityMode(exo:androidx.media3.common.Player,mode:String){
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
    private fun applyYoutubeNativeQuality(exo:ExoPlayer,index:Int){
        val preference=youtubeQualityPreference(index)
        val maxHeight=when(preference){
            "720p"->720
            "1080p"->1080
            else->Int.MAX_VALUE
        }
        exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE,maxHeight)
            .setForceHighestSupportedBitrate(preference=="Best available")
            .build()
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
        val returnToSettings=settingsScreenVisible
        if(index!=null && isYoutubeChannel(index)){
            AlertDialog.Builder(this).setTitle("YouTube picture quality")
                .setItems(arrayOf("Best available","Prefer 1080p","Prefer 720p","Auto (YouTube controls resolution)","Playback quality details")){
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
        var pending=selected
        previewQualityIndex=index ?: active
        previewQualityMode=if(key!=null && pending==0) prefs.getString("quality_global","Auto") ?: "Auto" else options[pending]
        val dialog=AlertDialog.Builder(this)
            .setTitle(if(index!=null)numberFor(index).toString()+". "+channels[index].name+" Settings — Picture Quality" else "Default stream quality")
            .setSingleChoiceItems(options,selected){_,choice->
                pending=choice
                previewQualityMode=if(key!=null && choice==0) prefs.getString("quality_global","Auto") ?: "Auto" else options[choice]
                if(index==null || index==active)player?.let{applyQualityMode(it,previewQualityMode!!)}
            }
            .setPositiveButton("OK"){_,_->
                val editor=prefs.edit()
                if(key!=null){
                    if(pending==0)editor.remove(key) else editor.putString(key,options[pending])
                }else{
                    editor.putString("quality_global",options[pending])
                    prefs.all.keys.filter{it.startsWith("quality_channel_")}.forEach{editor.remove(it)}
                }
                editor.apply()
                if(index==null || index==active)applyQuality()
                showBanner("Stream quality saved")
            }
            .setNegativeButton("Cancel",null).create()
        dialog.setOnShowListener{
            val list=dialog.listView ?:return@setOnShowListener
            list.choiceMode=android.widget.ListView.CHOICE_MODE_SINGLE
            list.setItemChecked(selected,true);list.setSelection(selected)
            list.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
                override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:View?,position:Int,id:Long){
                    pending=position;list.setItemChecked(position,true)
                    previewQualityMode=if(key!=null && position==0) prefs.getString("quality_global","Auto") ?: "Auto" else options[position]
                    if(index==null || index==active)player?.let{applyQualityMode(it,previewQualityMode!!)}
                }
                override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
            }
        }
        dialog.setOnDismissListener{
            previewQualityMode=null
            previewQualityIndex=null
            if(index==null || index==active)applyQuality()
            if(returnToSettings)settings()
        }
        dialog.show()
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
            }catch(e:Exception){HomeTvDiagnostics.w("HomeTV","Video size adjustment",e)}
        }
    }
    private fun selectScreenMode(channelIndex:Int?=null){
        val returnToSettings=settingsScreenVisible
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
            .setTitle(if(target!=null)numberFor(target).toString()+". "+channels[target].name+" Settings — Aspect Ratio" else "Default screen fit")
            .setSingleChoiceItems(labels,selected){_,position->
                // Highlighting previews the aspect ratio; only OK saves it.
                preview(position)
            }
            .setPositiveButton("OK"){_,_->commit()}
            .setNegativeButton("Cancel",null).create()
        dialog.setOnDismissListener{
            previewScreenMode=null
            applyVideoSize()
            if(confirmed && active>=0)showBanner("Screen: "+screenMode())
            if(returnToSettings)settings()
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
    // Ambiguous HTTP links are classified off the UI thread. This does not
    // probe obvious direct streams, so normal IPTV channel switching stays fast.
    private val urlRouteCache=mutableMapOf<String,Pair<String,Long>>()
    private fun routeHttpChannel(index:Int){
        val c=channels.getOrNull(index) ?:return
        val url=c.url
        val now=android.os.SystemClock.elapsedRealtime()
        val cached=urlRouteCache[url]
        if(cached!=null && now-cached.second<3600000L){
            playIndexResolved(index,cached.first=="web")
            return
        }
        // Persist URL classification so restarts do not repeat a network sniff
        // before the last-watched channel can begin playing.
        val routeKey="http_route_"+hash(url)
        val persistedRoute=prefs.getString(routeKey,"") ?: ""
        val persistedAt=prefs.getLong(routeKey+"_time",0L)
        val wallNow=System.currentTimeMillis()
        if((persistedRoute=="native" || persistedRoute=="web") &&
            persistedAt>0L && wallNow>=persistedAt &&
            wallNow-persistedAt<24L*60L*60L*1000L){
            urlRouteCache[url]=Pair(persistedRoute,now)
            playIndexResolved(index,persistedRoute=="web")
            return
        }
        val requestedGeneration=++httpRouteGeneration
        thread(name="HomeTV-URL-Classifier"){
            var route="native"
            var classificationConfirmed=false
            try{
                val connection=(URL(url).openConnection() as java.net.HttpURLConnection)
                try{
                    connection.instanceFollowRedirects=true
                    connection.connectTimeout=1800
                    connection.readTimeout=1800
                    connection.requestMethod="GET"
                    connection.setRequestProperty("Range","bytes=0-511")
                    c.headers.forEach{(k,v)->
                        if(!k.equals("Host",true) && !k.equals("Range",true))connection.setRequestProperty(k,v)
                    }
                    val responseCode=connection.responseCode
                    classificationConfirmed=responseCode in 200..299
                    val mime=connection.contentType.orEmpty().substringBefore(';').trim().lowercase(java.util.Locale.ROOT)
                    val finalPath=connection.url.path.lowercase(java.util.Locale.ROOT)
                    val sample=if(classificationConfirmed){
                        connection.inputStream.use{input->
                            val buffer=ByteArray(512)
                            val count=input.read(buffer)
                            if(count>0)String(buffer,0,count,Charsets.UTF_8) else ""
                        }
                    }else ""
                    val prefix=sample.trimStart().lowercase(java.util.Locale.ROOT)
                    val playlist=prefix.startsWith("#extm3u")
                    val dashXml=prefix.contains("<mpd") &&
                        (prefix.startsWith("<?xml") || prefix.startsWith("<mpd"))
                    route=when{
                        mime.contains("text/html") || prefix.startsWith("<!doctype html") ||
                            prefix.startsWith("<html")->"web"
                        mime.contains("mpegurl") || mime.contains("dash+xml") ||
                            mime.startsWith("video/") || mime.startsWith("audio/") ||
                            mime.contains("octet-stream") || playlist || dashXml ||
                            finalPath.endsWith(".m3u8") || finalPath.endsWith(".mpd")->"native"
                        else->"web"
                    }
                }finally{connection.disconnect()}
            }catch(_:Exception){classificationConfirmed=false}
            handler.post{
                if(requestedGeneration!=httpRouteGeneration || !licenseReady)return@post
                if(index !in channels.indices || channels[index].url!=url)return@post
                if(classificationConfirmed){
                    urlRouteCache[url]=Pair(route,android.os.SystemClock.elapsedRealtime())
                    prefs.edit().putString(routeKey,route)
                        .putLong(routeKey+"_time",System.currentTimeMillis()).apply()
                }
                playIndexResolved(index,classificationConfirmed && route=="web")
            }
        }
    }
    private var httpRouteGeneration=0
    private var routeWebOverride:Boolean?=null
    private fun playIndexResolved(index:Int,webRoute:Boolean){
        routeWebOverride=webRoute
        try{playIndex(index)}finally{routeWebOverride=null}
    }
    private fun isWebTv(c:Channel):Boolean{
        val u=try{Uri.parse(c.url)}catch(_:Exception){return false}
        val path=(u.path ?: "").lowercase(java.util.Locale.ROOT)
        if(streamKind(c.url) in setOf("hls","dash","smooth","file","rtsp","rtmp","vlc","flash"))return false
        if(u.scheme!="http" && u.scheme!="https")return false
        if(c.mediaType=="web" || (c.webChannel.isNotBlank() && !c.webChannel.equals("no",true)))return true
        // Website URLs in ordinary M3U playlists should use Web TV even without
        // explicit media-type=web. Preserve direct streams and YouTube handling.
        val host=u.host?.lowercase(java.util.Locale.ROOT) ?:return false
        if(host=="cmalayalamtv.com" || host=="www.cmalayalamtv.com" ||
            host=="bhoomtv.org" || host=="www.bhoomtv.org")return true
        return path.endsWith("/livetv") || path.startsWith("/live/") ||
            path.startsWith("/channel/") || path.endsWith("/live-tv") ||
            path.endsWith("/watch") || path.endsWith("/player") ||
            path.endsWith("/stream")
    }
    private fun webTvJavascript():String = """(function(){
        try {
          if(!document.getElementById('myhometv-web-style')){
            var style=document.createElement('style');style.id='myhometv-web-style';
            style.textContent='html,body{background:#000!important;margin:0!important;overflow:hidden!important}video{object-fit:contain!important;max-width:100vw!important;max-height:100vh!important}';
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
            player.controls=false;player.muted=false;player.volume=1;
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
          // Some embedded sites keep their video inside a cross-origin iframe.
          // Preserve the iframe as a whole rather than trying to access its DOM.
          // Only suppress surrounding website chrome after a visible player exists.
          if(primary && !document.getElementById('myhometv-player-mask')){
            var mask=document.createElement('style');
            mask.id='myhometv-player-mask';
            mask.textContent='html,body{overflow:hidden!important}';
            document.head.appendChild(mask);
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
    private var webTvDirectoryUrl:String?=null
    private var webTvDirectoryEntries:List<Pair<String,String>> = emptyList()
    private fun showWebTvDirectory(viewer:WebView,index:Int,screen:FrameLayout){
        if(web!==viewer || active!=index || videoScreen!==screen)return
        val entries=webTvDirectoryEntries
        if(entries.isEmpty())return
        AlertDialog.Builder(this)
            .setTitle("SELECT CHANNEL  •  "+channels[index].name)
            .setItems(entries.map{it.first}.toTypedArray()){_,selected->
                if(web!==viewer || active!=index || videoScreen!==screen)return@setItems
                viewer.loadUrl(entries[selected].second)
            }
            .setNegativeButton("Back to Web TV"){_,_->mediaLibrary("web")}
            .show()
    }
    private fun inspectWebTvDirectory(viewer:WebView,index:Int,screen:FrameLayout){
        if(web!==viewer || active!=index || videoScreen!==screen)return
        val js="""(function(){
          var seen={},channels=[];
          document.querySelectorAll('a[href*="/live/"]').forEach(function(a){
            var u=a.href||'', name=(a.innerText||a.getAttribute('title')||
              (a.querySelector('img')||{}).alt||'').replace(/\\s+/g,' ').trim();
            if(!name && a.parentElement)name=(a.parentElement.innerText||'').replace(/\\s+/g,' ').trim();
            if(!name||name.length>75||seen[u]||!/^https?:/.test(u))return;
            seen[u]=true;channels.push({name:name,url:u});
          });
          return JSON.stringify(channels.slice(0,180));
        })();"""
        viewer.evaluateJavascript(js){raw->
            if(web!==viewer || active!=index || videoScreen!==screen)return@evaluateJavascript
            try{
                val decoded=org.json.JSONTokener(raw).nextValue() as? String ?:raw
                val list=org.json.JSONArray(decoded)
                val entries=mutableListOf<Pair<String,String>>()
                for(n in 0 until list.length()){
                    val item=list.optJSONObject(n)?:continue
                    val title=item.optString("name").trim()
                    val url=item.optString("url")
                    if(title.isNotBlank() && url.startsWith("https://bhoomtv.org/live/"))
                        entries.add(title to url)
                }
                if(entries.isNotEmpty()){
                    webTvDirectoryEntries=entries
                    showWebTvDirectory(viewer,index,screen)
                }else inspectWebTv(viewer,index,screen,true)
            }catch(_:Exception){inspectWebTv(viewer,index,screen,true)}
        }
    }
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
        if(pendingPlaybackDrawerChannelKey!=null && pendingPlaybackDrawerChannelKey!=channelKey(c))
            pendingPlaybackDrawerChannelKey=null
        if(youtubeNativeAttemptedUrl!=null && youtubeNativeAttemptedUrl!=c.url){
            youtubeNativeGeneration++
            youtubeNativeAttemptedUrl=null
            youtubeNativeStreamUrl=null
            youtubeResolveTimeout?.let{handler.removeCallbacks(it)}
            youtubeResolveTimeout=null
        }
        if(c.source in lockedPlaylistNames() && c.source !in unlockedPlaylistsThisSession){
            openLockedPlaylist(c.source){playIndex(index)}
            return
        }
        if(c.group in lockedGroupNames() && c.group !in unlockedGroupsThisSession){
            openLockedGroup(c.group){playIndex(index)}
            return
        }
        // Do not contact or classify a locked channel before PIN approval.
        if(unlockedChannel!=id(c) && isLocked(c)){
            requirePin {unlockedChannel=id(c);playIndex(index)}
            return
        }
        // Explicit and known stream URLs bypass classification. Unknown HTTP
        // URLs get a short bounded content-type check before choosing WebView.
        if(routeWebOverride==null && streamKind(c.url)=="http" &&
            !isWebTv(c) && !c.url.contains("youtube.com",true) &&
            !c.url.contains("youtu.be",true)){
            routeHttpChannel(index)
            return
        }
        if(streamKind(c.url)=="flash"){
            AlertDialog.Builder(this).setTitle("Unsupported Flash stream")
                .setMessage("Adobe Flash (.swf) cannot run on Android TV. Add a modern website or HTML5 stream URL instead.")
                .setPositiveButton("OK",null).show()
            return
        }
        // Invalidate outstanding classification when a channel actually starts.
        httpRouteGeneration++
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
        handler.postDelayed({if(active==index)applyChannelVolume()},400)
        if(unlockedChannel!=id(c)) unlockedChannel=null
        prefs.edit().putString("last_url",c.url).putString("last_url_"+c.source,c.url).apply()
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
                (routeWebOverride ?: isWebTv(c))
        }catch(_:Exception){false}
        // Try a publicly exposed HLS livestream natively first. The resolver is
        // bounded to eight seconds and never bypasses authentication or access controls.
        // Unsupported, slow or failed attempts fall back to the original WebView page.
        if(HomeTvPolicies.shouldAttemptNativeYoutube(
                eligibleVideo=youtubeUri!=null && hasNetworkConnection(),
                alreadyAttempted=youtubeNativeAttemptedUrl==c.url,
                hasResolvedStream=youtubeNativeStreamUrl!=null,
                forceWeb=routeWebOverride==true
            )){
            youtubeNativeAttemptedUrl=c.url
            val attemptGeneration=++youtubeNativeGeneration
            val sourceScreen=screen
            val timeout=Runnable{
                if(attemptGeneration==youtubeNativeGeneration && active==index &&
                    videoScreen===sourceScreen && youtubeNativeStreamUrl==null){
                    youtubeResolveTimeout=null
                    HomeTvDiagnostics.i("HomeTV","YouTube native discovery timed out; using WebView fallback")
                    youtubeNativeTransition=true
                    playIndex(index)
                }
            }
            updateClock()
            screen.requestFocus()
            showBanner("Checking YouTube stream…")
            youtubeResolveTimeout=timeout
            handler.postDelayed(timeout,YouTubeStreamResolver.TIMEOUT_MS.toLong())
            thread(name="HomeTV-youtube-resolver"){
                val stream=try{YouTubeStreamResolver.resolve(c.url)}catch(e:Exception){
                    HomeTvDiagnostics.w("HomeTV","YouTube native discovery failed",e);null
                }
                runOnUiThread{
                    if(attemptGeneration!=youtubeNativeGeneration || active!=index ||
                        videoScreen!==sourceScreen || isFinishing || isDestroyed)return@runOnUiThread
                    youtubeResolveTimeout?.let{handler.removeCallbacks(it)}
                    youtubeResolveTimeout=null
                    if(stream!=null){
                        youtubeNativeStreamUrl=stream.url
                        HomeTvDiagnostics.i("HomeTV","Public YouTube HLS manifest found; starting native Media3 playback")
                    }else{
                        HomeTvDiagnostics.i("HomeTV","No eligible public YouTube HLS manifest; using WebView fallback")
                    }
                    youtubeNativeTransition=true
                    playIndex(index)
                }
            }
            return
        }
        if((youtubeUri!=null && youtubeNativeStreamUrl==null) || webPage){
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
                    if(youtubeUri!=null && web===view && active==index && videoScreen===screen &&
                        youtubePageInitializedGeneration!=playbackGeneration){
                        youtubePageInitializedGeneration=playbackGeneration
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
                              // Keep mute accessible but suppress YouTube's other
                              // transport/settings buttons that overlap TV controls.
                              '.ytp-chrome-bottom .ytp-left-controls > :not(.ytp-volume-area),'+
                              '.ytp-chrome-bottom .ytp-right-controls > :not(.ytp-volume-area),'+
                              '.ytp-settings-button,.ytp-subtitles-button,.ytp-size-button,.ytp-fullscreen-button,'+
                              '.ytp-miniplayer-button,.ytp-next-button,.ytp-prev-button,.ytp-play-button,'+
                              '.ytp-progress-bar-container{display:none!important;pointer-events:none!important}'+
                              '.ytp-volume-area,.ytp-mute-button{display:inline-flex!important;visibility:visible!important;pointer-events:auto!important}'+
                              '.ytp-chrome-top .ytp-title-container,.ytp-chrome-top .ytp-title-text{display:none!important}';
                            (document.head||document.documentElement).appendChild(style);
                        })();"""
                        view.evaluateJavascript(fullScreenCss,null)
                        requestYoutubeStartupAudio(view,index,screen)
                        // For live broadcasts let YouTube manage adaptive quality during
                        // startup. Forcing a quality range in the first seconds can
                        // restart a live/DVR segment and make the picture repeat.
                        if(c.mediaType!="live")requestYoutubeEnhancedQuality(view,index,screen)
                        beginYoutubeProgressTracking(view,index,screen)
                    }
                }
                override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){
                    if(request.isForMainFrame && web===view){
                        HomeTvDiagnostics.w("HomeTV","YouTube page error: "+error.description)
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
                viewer.settings.javaScriptCanOpenWindowsAutomatically=true
                viewer.settings.setSupportMultipleWindows(false)
                viewer.settings.mixedContentMode=android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                android.webkit.CookieManager.getInstance().setAcceptCookie(true)
                android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(viewer,true)
                viewer.webViewClient=object:WebViewClient(){
                    override fun onPageFinished(view:WebView,url:String){
                        super.onPageFinished(view,url)
                        if(web===view && active==index && videoScreen===screen){
                            if(url.startsWith("https://bhoomtv.org/channel/")){
                                webTvDirectoryUrl=url
                                inspectWebTvDirectory(view,index,screen)
                            }else{
                                longArrayOf(1100L,3000L,6500L).forEach{delay->
                                    handler.postDelayed({inspectWebTv(view,index,screen,false)},delay)
                                }
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
                    if(key==KeyEvent.KEYCODE_BACK){
                        if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)onBackPressed()
                        return@setOnKeyListener true
                    }
                    if(event.action==KeyEvent.ACTION_DOWN && (key==KeyEvent.KEYCODE_MENU ||
                        key==KeyEvent.KEYCODE_INFO)){
                        inspectWebTv(viewer,index,screen,true);true
                    }else false
                }
                val preferred=prefs.getString("web_preferred_"+c.url.hashCode(),"").orEmpty()
                val isBhoomDirectory=c.url.startsWith("https://bhoomtv.org/channel/")
                viewer.loadUrl(if(!isBhoomDirectory && (preferred.startsWith("https://") || preferred.startsWith("http://")))preferred else c.url)
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
        }else if(engineFor(c)=="LibVLC" && youtubeNativeStreamUrl==null){
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
            val gainProcessor=ChannelGainAudioProcessor(channelVolumeGain(c))
            activeChannelGainProcessor=gainProcessor
            val renderersFactory=HomeTvRenderersFactory(this,gainProcessor)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                .setEnableDecoderFallback(true)
            val exo=ExoPlayer.Builder(this,renderersFactory)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory)).setLoadControl(loadControl).build()
            exo.setAudioAttributes(AudioAttributes.DEFAULT,true)
            exo.trackSelectionParameters=exo.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(if(prefs.getBoolean("audio_use_stream_default",false)) null else preferredAudioLanguage())
                .build()
            exo.volume=1f
            player=exo
            applyQuality()
            if(youtubeNativeStreamUrl!=null)applyYoutubeNativeQuality(exo,index)
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
                    !exo.isPlaying){
                    if(youtubeNativeStreamUrl!=null && fallbackYouTubeNativeToWeb(index,screen))return@Runnable
                    HomeTvDiagnostics.w("HomeTV","Stream did not start playing before startup timeout: "+c.name+
                        " state="+exo.playbackState+" error="+exo.playerError?.errorCodeName)
                    handleEngineFailure(index,screen)
                }
            }
            startupWatchdog=startupTimeout
            // HLS sources can take longer to open their first segment.
            val startupLimit=if(streamKind(c.url)=="hls")12000L else 8000L
            handler.postDelayed(startupTimeout,startupLimit)
            var wasReady=false
            var bufferingRecoveryTask:Runnable?=null
            // Detect video output stalls independently of the audio clock.
            // Some Android TV decoders keep audio moving while the picture freezes.
            var media3PlayStartedAt=0L
            var lastRenderedVideoFrames=-1
            var lastVideoFrameProgressAt=0L
            var videoFreezeHandled=false
            var pictureEffectRecoveryAttempted=false
            fun recoverNativeMedia3Video(now:Long):Boolean{
                if(pictureEffectRecoveryAttempted || selectedPicturePreset(c)=="Standard")return false
                pictureEffectRecoveryAttempted=true
                return try{
                    val position=exo.currentPosition
                    // GL picture effects can expose decoder/surface bugs on some TV chipsets.
                    // Retry the exact same URL on Media3's native video surface first.
                    exo.setVideoEffects(emptyList())
                    exo.seekTo(position)
                    exo.play()
                    lastRenderedVideoFrames=-1
                    lastVideoFrameProgressAt=now
                    HomeTvDiagnostics.w("HomeTV","Media3 picture-effect pipeline stalled; retrying native rendering for "+c.name)
                    true
                }catch(e:Exception){
                    HomeTvDiagnostics.w("HomeTV","Native Media3 rendering recovery failed for "+c.name,e)
                    false
                }
            }
            val videoFrameWatchdog=object:Runnable{
                override fun run(){
                    if(player!==exo || active!=index || videoScreen!==screen)return
                    val now=android.os.SystemClock.elapsedRealtime()
                    if(exo.isPlaying && exo.videoFormat!=null){
                        if(media3PlayStartedAt==0L)media3PlayStartedAt=now
                        val counters:DecoderCounters?=exo.videoDecoderCounters
                        if(counters!=null){
                            counters.ensureUpdated()
                            val rendered=counters.renderedOutputBufferCount
                            // Counters can reset when MediaCodec is recreated; any
                            // change is progress, not only an increase.
                            if(rendered!=lastRenderedVideoFrames){
                                lastRenderedVideoFrames=rendered
                                lastVideoFrameProgressAt=now
                            }
                        }
                        val noVideoFrameYet=lastVideoFrameProgressAt==0L &&
                            now-media3PlayStartedAt>=20000L
                        val videoFramesStopped=lastVideoFrameProgressAt>0L &&
                            now-lastVideoFrameProgressAt>=20000L
                        if((noVideoFrameYet || videoFramesStopped) && !videoFreezeHandled){
                            HomeTvDiagnostics.e("HomeTV","Media3 video frame output stalled while playback continued: "+c.name+
                                " renderedFrames="+(counters?.renderedOutputBufferCount ?: -1)+
                                " positionMs="+exo.currentPosition)
                            if(recoverNativeMedia3Video(now)){
                                handler.postDelayed(this,5000L)
                                return
                            }
                            videoFreezeHandled=true
                            handleEngineFailure(index,screen)
                            return
                        }
                    }
                    handler.postDelayed(this,5000L)
                }
            }
            handler.postDelayed(videoFrameWatchdog,5000L)
            exo.addAnalyticsListener(object:AnalyticsListener{
                override fun onRenderedFirstFrame(
                    eventTime:AnalyticsListener.EventTime,
                    output:Any,
                    renderTimeMs:Long
                ){
                    if(player===exo && active==index){
                        val now=android.os.SystemClock.elapsedRealtime()
                        lastVideoFrameProgressAt=now
                        exo.videoDecoderCounters?.let{counters->
                            counters.ensureUpdated()
                            lastRenderedVideoFrames=counters.renderedOutputBufferCount
                        }
                        // Do not mark a Media3 source as working merely because its
                        // audio starts; require an actual rendered video frame.
                        rememberWorking(c)
                    }
                }
            })
            exo.addListener(object:Player.Listener{
                override fun onTracksChanged(tracks:androidx.media3.common.Tracks){
                    if(player===exo && active==index)applyAudioTrackPreference(c,exo)
                }
                override fun onIsPlayingChanged(isPlaying:Boolean){
                    if(isPlaying && player===exo && active==index){
                        startupWatchdog?.let{handler.removeCallbacks(it)}
                        startupWatchdog=null
                        // Audio-only entries can be confirmed after track selection
                        // settles. Video streams are confirmed by onRenderedFirstFrame.
                        if(exo.videoFormat==null){
                            handler.postDelayed({
                                if(player===exo && active==index && exo.isPlaying && exo.videoFormat==null)
                                    rememberWorking(c)
                            },2500L)
                        }
                    }
                }
                override fun onPlaybackStateChanged(state:Int){
                    if(state==Player.STATE_BUFFERING){
                        autoBufferStableSince=0L
                        autoBufferStalled()
                        if(wasReady){
                            bufferingRecoveryTask?.let{handler.removeCallbacks(it)}
                            val recovery=Runnable{
                                if(active==index && videoScreen===screen && player===exo &&
                                    exo.playbackState==Player.STATE_BUFFERING){
                                    if(youtubeNativeStreamUrl!=null && fallbackYouTubeNativeToWeb(index,screen))return@Runnable
                                    handleEngineFailure(index,screen)
                                }
                            }
                            bufferingRecoveryTask=recovery
                            handler.postDelayed(recovery,20000L)
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
                        // STATE_READY means prepared, not necessarily playing.
                        // Keep the startup watchdog active until isPlaying becomes true.
                        applyVideoSize()
                        scheduleQualityUpgrade()
                        retryTask?.let{handler.removeCallbacks(it)}
                        retryTask=null
                    }
                }
                override fun onPlayerError(error:PlaybackException){
                    val httpStatus=(error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    HomeTvDiagnostics.e("HomeTV", "Playback error for "+c.name+" HTTP="+httpStatus, error)
                    if(youtubeNativeStreamUrl!=null && fallbackYouTubeNativeToWeb(index,screen))return
                    handleEngineFailure(index,screen)
                }
            })
            try{
                applyMedia3PicturePreset(exo,c)
                exo.setMediaItem(mediaItemForStream(youtubeNativeStreamUrl ?: c.url))
                exo.prepare()
                exo.play()
            }catch(e:Exception){
                if(youtubeNativeStreamUrl!=null && fallbackYouTubeNativeToWeb(index,screen))return
                showBanner("Cannot play ${c.name}: ${e.message}",true);handleEngineFailure(index,screen)
            }
        }
        updateClock()
        screen.requestFocus()
        showChannelInfo(index)
        if(pendingPlaybackDrawerChannelKey==channelKey(c)){
            pendingPlaybackDrawerChannelKey=null
            handler.post{if(active==index && videoScreen===screen)showPlaybackSettings()}
        }
    }
    private fun fallbackYouTubeNativeToWeb(index:Int,screen:FrameLayout):Boolean{
        if(!HomeTvPolicies.shouldFallbackNativeYoutube(
                hasResolvedStream=youtubeNativeStreamUrl!=null,
                isCurrentChannel=active==index,
                isSameScreen=videoScreen===screen
            ))return false
        youtubeNativeStreamUrl=null
        youtubeNativeGeneration++
        youtubeResolveTimeout?.let{handler.removeCallbacks(it)}
        youtubeResolveTimeout=null
        showBanner("Native YouTube playback unavailable. Opening YouTube player…")
        youtubeNativeTransition=true
        playIndexResolved(index,true)
        return true
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
    private fun startVlc(c:Channel,index:Int,screen:FrameLayout){
        try{
            val cache=bufferValues()[0].coerceAtLeast(300)
            // Keep hardware decoding automatic and full-resolution output enabled.
            // The adaptive stream selector below is capped only by an explicit user preset.
            val options=arrayListOf(
                "--network-caching="+cache,
                "--live-caching="+cache,
                "--http-reconnect",
                "--avcodec-hw=any",
                "--no-video-title-show"
            )
            val picture=selectedPictureValues(c)
            options.addAll(HomeTvPolicies.libVlcPictureOptions(
                selectedPicturePreset(c),picture.brightness,picture.contrast,picture.saturation
            ))
            val core=LibVLC(this,options)
            val vlc=VlcPlayer(core)
            vlcCore=core
            vlcPlayer=vlc
            try{vlc.volume=(channelVolumeGain(c)*100).toInt().coerceIn(0,200)}catch(_:Exception){}
            // TextureView frame callbacks let HomeTV detect the real video output,
            // not just an advancing audio/media clock.
            val surface=TextureView(this)
            screen.addView(surface,0,FrameLayout.LayoutParams(-1,-1))
            vlc.vlcVout.setVideoView(surface)
            vlc.vlcVout.attachViews()
            val vlcSurfaceListener=surface.surfaceTextureListener
            var lastVlcVideoFrameAt=0L
            surface.surfaceTextureListener=object:TextureView.SurfaceTextureListener{
                override fun onSurfaceTextureAvailable(texture:android.graphics.SurfaceTexture,width:Int,height:Int){
                    vlcSurfaceListener?.onSurfaceTextureAvailable(texture,width,height)
                }
                override fun onSurfaceTextureSizeChanged(texture:android.graphics.SurfaceTexture,width:Int,height:Int){
                    vlcSurfaceListener?.onSurfaceTextureSizeChanged(texture,width,height)
                }
                override fun onSurfaceTextureDestroyed(texture:android.graphics.SurfaceTexture):Boolean=
                    vlcSurfaceListener?.onSurfaceTextureDestroyed(texture) ?: true
                override fun onSurfaceTextureUpdated(texture:android.graphics.SurfaceTexture){
                    vlcSurfaceListener?.onSurfaceTextureUpdated(texture)
                    lastVlcVideoFrameAt=android.os.SystemClock.elapsedRealtime()
                }
            }
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
            var hasVideoOutput=false
            var vlcStartedAt=0L
            var vlcFrameConfirmed=false
            var lastHealthy=android.os.SystemClock.elapsedRealtime()
            val stallCheck=object:Runnable{
                override fun run(){
                    if(vlcPlayer!==vlc || active!=index || videoScreen!==screen)return
                    if(started && vlc.isPlaying && !failed && prefs.getBoolean("auto_reconnect",true)){
                        val now=android.os.SystemClock.elapsedRealtime()
                        val hasVideoTrack=try{vlc.videoTracks?.any{it.id>=0}==true}catch(_:Exception){false}
                        val videoExpected=hasVideoOutput || hasVideoTrack
                        val videoFrameAge=if(lastVlcVideoFrameAt>0L)now-lastVlcVideoFrameAt
                            else now-vlcStartedAt
                        if(videoExpected && lastVlcVideoFrameAt>0L && !vlcFrameConfirmed){
                            vlcFrameConfirmed=true
                            rememberWorking(c)
                        }
                        val stalled=HomeTvPolicies.isVideoOutputStalled(
                            vlc.isPlaying,videoExpected,videoFrameAge,now-lastHealthy
                        )
                        if(stalled){
                            failed=true
                            autoBufferStalled()
                            HomeTvDiagnostics.w("HomeTV","LibVLC "+(if(videoExpected)"video frames" else "media clock")+
                                " stalled for "+c.name+" vout="+hasVideoOutput+
                                " frameAgeMs="+videoFrameAge+" mediaTimeMs="+vlc.time)
                            handleEngineFailure(index,screen)
                            return
                        }
                    }
                    handler.postDelayed(this,5000)
                }
            }
            handler.postDelayed(stallCheck,5000)
            val watchdog=Runnable{
                if(!started && !failed && active==index && vlcPlayer===vlc){
                    failed=true
                    HomeTvDiagnostics.w("HomeTV","LibVLC startup timeout for "+c.name)
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
                            if(vlcPlayer===vlc && active==index && !failed)
                                applyVlcAudioTrackPreference(c,vlc)
                        },700L)
                        handler.postDelayed({
                            if(vlcPlayer===vlc && active==index && !failed &&
                                android.os.SystemClock.elapsedRealtime()-lastHealthy<12000L)
                                recoveryAttempts.remove(c.url)
                        },60000L)
                        vlcStartedAt=android.os.SystemClock.elapsedRealtime()
                        lastHealthy=vlcStartedAt
                        handler.postDelayed({
                            if(vlcPlayer===vlc && active==index && !failed &&
                                (lastVlcVideoFrameAt>0L || !hasVideoOutput))
                                rememberWorking(c)
                        },1500L)
                        autoBufferHealthy()
                        handler.removeCallbacks(watchdog)
                    }
                    VlcPlayer.Event.TimeChanged, VlcPlayer.Event.PositionChanged->{
                        // This is used only for audio-only streams. Video health is
                        // determined from TextureView's actual frame-update callbacks.
                        if(!hasVideoOutput)lastHealthy=android.os.SystemClock.elapsedRealtime()
                    }
                    VlcPlayer.Event.Vout->{
                        // Vout means a video output was created, not that a frame
                        // reached the TextureView. Only onSurfaceTextureUpdated
                        // is allowed to confirm a real presented frame.
                        hasVideoOutput=event.voutCount>0
                    }
                    VlcPlayer.Event.Buffering->{
                        // A buffering callback is not proof of progress.
                        // Leave lastHealthy unchanged so a persistent freeze
                        // triggers same-engine recovery after the stall timeout.
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
            val startupLimit=if(streamKind(c.url)=="hls")12000L else 8000L
            handler.postDelayed(watchdog,startupLimit)
            val media=VlcMedia(core,Uri.parse(c.url))
            c.headers["User-Agent"]?.let{media.addOption(":http-user-agent="+it)}
            c.headers["Referer"]?.let{media.addOption(":http-referrer="+it)}
            media.addOption(":network-caching="+cache)
            media.addOption(":live-caching="+cache)
            // LibVLC adaptive HLS/DASH quality: do not cap Auto/Best
            // Available at a low resolution. Fixed presets provide an upper
            // resolution preference when the stream exposes variants.
            val quality=qualityMode(index)
            // Auto/Best Available never impose a low-resolution ceiling. Explicit
            // presets are the only modes allowed to cap the adaptive rendition.
            HomeTvPolicies.libVlcAdaptiveOptions(quality).forEach{media.addOption(it)}
            vlc.media=media
            media.release()
            vlc.play()
            handler.postDelayed({if(vlcPlayer===vlc)applyVideoSize()},1000)
        }catch(e:Exception){
            HomeTvDiagnostics.e("HomeTV","LibVLC startup failed",e)
            showBanner("LibVLC error: "+(e.message?:"unknown"),true)
            handleEngineFailure(index,screen)
        }
    }
    // The surfing sequence is already filtered and ordered in the background
    // cache. Use its prebuilt channel-key lookup so remote navigation is O(1),
    // even with 10k–20k channel playlists.
    private fun nextSurfingIndex(current:Int,delta:Int,available:List<Int>):Int{
        if(available.isEmpty())return -1
        val key=channels.getOrNull(current)?.let{channelKey(it)}
        val direction=if(delta>=0)1 else -1
        if(available!==surfingCache){
            // Failure recovery passes a filtered copy that excludes the failed
            // channel. Walk the original cached order and choose the next allowed
            // entry, rather than treating the failed channel as position zero.
            val base=surfingCache
            val basePosition=key?.let{surfingIndexByChannelKey[it]}
            if(base!=null && basePosition!=null){
                val allowed=available.toHashSet()
                for(step in 1..base.size){
                    val candidate=base[(basePosition+direction*step+base.size)%base.size]
                    if(candidate in allowed)return candidate
                }
            }
            val filteredPosition=available.indexOfFirst{candidate->
                channels.getOrNull(candidate)?.let{channelKey(it)==key}==true
            }
            if(filteredPosition<0)return if(direction>0)available.first() else available.last()
            return available[(filteredPosition+direction+available.size)%available.size]
        }
        val position=key?.let{surfingIndexByChannelKey[it]}
        if(position==null)return if(direction>0)available.first() else available.last()
        return available[(position+direction+available.size)%available.size]
    }
    private fun switchChannel(delta:Int){
        clearChannelPreview()
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
        // Avoid building and sorting the full surfing list on the common
        // startup path when the saved channel is already present and unlocked.
        if(index>=0 && !isLocked(channels[index])){
            playIndex(index)
            return
        }
        val available=surfingIndices()
        if(available.isNotEmpty())playIndex(preferredChannel(available.first()))
        else allChannels()
    }
    private fun liveSettings(){
        if(active<0){settings();return}
        val items=arrayOf(
            "Playback engine: "+(prefs.getString("engine","Media3")?:"Media3"),
            "Buffering profile: "+bufferProfile(),
            "Default stream quality: "+(prefs.getString("quality_global","Auto")?:"Auto"),
            "Default screen fit: "+(prefs.getString("screen_mode","Fit")?:"Fit"),
            "Automatic reconnection: "+if(prefs.getBoolean("auto_reconnect",true))"ON" else "OFF",
            "Resume previous channel: "+if(prefs.getBoolean("resume",true))"ON" else "OFF",
            "Clock display",
            "Playlist manager (stop playback)",
            "Home (stop playback)",
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
    private fun launchTextExport(fileName:String,mime:String,payload:String,requestCode:Int){
        pendingTextExport=payload
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE)
            type=mime
            putExtra(Intent.EXTRA_TITLE,fileName)
        }
        try{startActivityForResult(intent,requestCode)}catch(e:Exception){
            pendingTextExport=null
            Toast.makeText(this,"Unable to open the TV file picker: "+e.message,Toast.LENGTH_LONG).show()
        }
    }
    private fun exportSettings(){
        val excluded=setOf("license_key","license_device","license_expiry","pin","last_url","startup_in_progress","startup_crash_count","safe_startup_mode","last_processed_exit_timestamp")
        val data=JSONObject()
        prefs.all.forEach{(key,value)->
            if(key in excluded || key.startsWith("license_") || key.startsWith("signed_license_") ||
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
        AlertDialog.Builder(this).setTitle("Save settings backup")
            .setMessage("The backup includes playlist URLs, which may contain provider credentials. Licence activation and the parental PIN are excluded. Save the file only to a location you trust.")
            .setPositiveButton("Choose location"){_,_->
                launchTextExport("HomeTV-settings-backup.json","application/json",payload,exportBackupRequest)
            }.setNegativeButton("Cancel",null).show()
    }
    private fun mergePlaylistBackup(incoming:String):String{
        val merged=linkedMapOf<String,JSONObject>()
        fun add(array:JSONArray){
            for(i in 0 until array.length()){
                val item=array.optJSONObject(i) ?: continue
                val url=item.optString("url").trim()
                if(url.isBlank())continue
                merged[url.lowercase(java.util.Locale.ROOT)]=JSONObject(item.toString())
            }
        }
        add(JSONArray(prefs.getString("playlists","[]")))
        add(JSONArray(incoming))
        return JSONArray().apply{merged.values.forEach{put(it)}}.toString()
    }
    private fun mergeStringArrayBackup(key:String,incoming:String):String{
        val values=linkedSetOf<String>()
        fun add(raw:String){
            val array=JSONArray(raw)
            for(i in 0 until array.length()){
                val value=array.optString(i)
                if(value.isNotBlank())values.add(value)
            }
        }
        add(prefs.getString(key,"[]") ?: "[]")
        add(incoming)
        return JSONArray().apply{values.forEach{put(it)}}.toString()
    }
    private fun importSettings(){
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE)
            type="application/json"
            putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("application/json","text/plain","application/octet-stream"))
        }
        try{startActivityForResult(intent,importBackupRequest)}catch(e:Exception){
            Toast.makeText(this,"Unable to open the TV file picker: "+e.message,Toast.LENGTH_LONG).show()
        }
    }
    private fun restoreSettingsFromText(raw:String){
        try{
            val backup=JSONObject(raw)
            require(backup.optString("format")=="HomeTV-settings-v1")
            val values=backup.getJSONObject("settings")
            val staged=mutableListOf<Pair<String,Any>>()
            val iterator=values.keys()
            while(iterator.hasNext()){
                val key=iterator.next()
                if(key=="pin" || key=="last_url" || key in setOf("startup_in_progress","startup_crash_count","safe_startup_mode","last_processed_exit_timestamp") || key.startsWith("license_") || key.startsWith("signed_license_") ||
                    key.contains("password",ignoreCase=true) || key.contains("secret",ignoreCase=true) ||
                    key.contains("token",ignoreCase=true) || key.contains("credential",ignoreCase=true))continue
                val item=values.getJSONObject(key)
                val value:Any=when(item.getString("type")){
                    "string"->item.getString("value")
                    "boolean"->item.getBoolean("value")
                    "int"->item.getInt("value")
                    "long"->item.getLong("value")
                    "float"->item.getDouble("value").toFloat()
                    "strings"->{
                        val arr=item.getJSONArray("value")
                        (0 until arr.length()).map{arr.getString(it)}.toSet()
                    }
                    else->throw IllegalArgumentException("Unsupported setting type for "+key)
                }
                staged.add(key to value)
            }
            val incomingKeys=staged.map{it.first}.toSet()
            AlertDialog.Builder(this).setTitle("Choose restore method")
                .setItems(arrayOf("Merge with existing settings","Replace existing settings")){_,mode->
                    try{
                        val edit=prefs.edit()
                        if(mode==1){
                            prefs.all.keys.filter{key->
                                key !in incomingKeys && key!="pin" && key!="last_url" && key !in setOf("startup_in_progress","startup_crash_count","safe_startup_mode","last_processed_exit_timestamp") &&
                                    !key.startsWith("license_") && !key.startsWith("signed_license_") &&
                                    !key.contains("password",ignoreCase=true) &&
                                    !key.contains("secret",ignoreCase=true) &&
                                    !key.contains("token",ignoreCase=true) &&
                                    !key.contains("credential",ignoreCase=true)
                            }.forEach{edit.remove(it)}
                        }
                        staged.forEach{(key,incoming)->
                            val value:Any=when{
                                mode==0 && key=="playlists" && incoming is String->mergePlaylistBackup(incoming)
                                mode==0 && (key=="viewer_profiles" || key=="recent_channels" ||
                                    (key.startsWith("viewer_") && key.endsWith("_recent_channels"))) &&
                                    incoming is String->mergeStringArrayBackup(key,incoming)
                                mode==0 && incoming is Set<*>->
                                    (prefs.getStringSet(key,emptySet()) ?: emptySet()) + incoming.filterIsInstance<String>()
                                else->incoming
                            }
                            when(value){
                                is String->edit.putString(key,value)
                                is Boolean->edit.putBoolean(key,value)
                                is Int->edit.putInt(key,value)
                                is Long->edit.putLong(key,value)
                                is Float->edit.putFloat(key,value)
                                is Set<*>->edit.putStringSet(key,value.filterIsInstance<String>().toSet())
                            }
                        }
                        edit.apply()
                        Toast.makeText(this,(if(mode==0)"Merged " else "Replaced with ")+staged.size+
                            " settings. License and parental PIN were kept.",Toast.LENGTH_LONG).show()
                    }catch(e:Exception){
                        Toast.makeText(this,"Restore failed: "+e.message,Toast.LENGTH_LONG).show()
                    }
                }.setNegativeButton("Cancel",null).show()
        }catch(e:Exception){
            Toast.makeText(this,"Invalid backup: "+e.message,Toast.LENGTH_LONG).show()
        }
    }
    private fun showDiagnosticLogs(){
        val report=HomeTvDiagnostics.readReport()
        val preview=report.takeLast(30_000)
        val message=if(report.isBlank())"No diagnostic logs have been recorded yet."
            else preview+(if(report.length>preview.length)"\n\nShowing the latest entries. Use Save report to save the complete available report." else "")
        AlertDialog.Builder(this).setTitle("Diagnostic logs — last 30 days")
            .setMessage(message)
            .setPositiveButton("Save report"){_,_->exportDiagnosticReport()}
            .setNegativeButton("Close",null).show()
    }
    private fun exportDiagnosticReport(){
        val report=HomeTvDiagnostics.readReport()
        if(report.isBlank()){
            Toast.makeText(this,"No diagnostic logs are available yet.",Toast.LENGTH_SHORT).show()
            return
        }
        val stamp=java.text.SimpleDateFormat("yyyyMMdd-HHmm",java.util.Locale.ROOT).format(java.util.Date())
        launchTextExport("HomeTV-diagnostics-"+stamp+".txt","text/plain",report,exportDiagnosticsRequest)
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
    // Clear disposable app caches only. Never modify license preferences,
    // installation ID, playlist configuration, PIN, or server activation.
    private fun confirmClearCache(){
        AlertDialog.Builder(this).setTitle("Clear HomeTV cache")
            .setMessage("Delete downloaded playlist caches, temporary files and image cache? Your license, activation, playlists, PIN and settings will be kept. Playlists may need to download again.")
            .setPositiveButton("Clear cache"){_,_->
                // Do not invalidate a playing stream or reset channel selection.
                val files=filesDir.listFiles()?.filter{it.isFile && it.name.startsWith("playlist_") && it.name.endsWith(".m3u")} ?:emptyList()
                thread(name="HomeTV-clear-cache"){
                    var deleted=0
                    files.forEach{if(it.delete())deleted++}
                    fun clean(dir:java.io.File?){
                        dir?.listFiles()?.forEach{entry->
                            if(entry.isDirectory)clean(entry)
                            if(entry.delete())deleted++
                        }
                    }
                    clean(cacheDir)
                    externalCacheDir?.let{clean(it)}
                    runOnUiThread{
                        posterCache.evictAll()
                        Toast.makeText(this,"Cache cleared ("+deleted+" files). License remains active.",Toast.LENGTH_LONG).show()
                    }
                }
            }.setNegativeButton("Cancel",null).show()
    }

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
        root.removeAllViews()
        root.addView(tvText("⚙  SETTINGS",30f,0xFF8CE8FF.toInt()).apply{
            setPadding(tvDp(12),tvDp(15),0,tvDp(6))
        })
        root.addView(tvText("Personalize your TV experience",14f,0xFFB0C6D9.toInt()))
        settingsScreenVisible=true
        root.addView(tvSection("ACCOUNT & PLAYLISTS"))
        root.addView(button("Manage device activation"){surrenderLicense()})
        root.addView(button("Playlist manager"){managePlaylists()})
        root.addView(settingsToggle("Automatic playlist refresh","auto_playlist_check",true))
        root.addView(label("Checks the selected active playlist in the background after startup. Cached channels open first; unchanged playlists are not reparsed."))
        root.addView(button("Clear app cache"){confirmClearCache()})
        root.addView(button("Back up settings"){exportSettings()})
        root.addView(button("Restore settings"){importSettings()})
        root.addView(tvSection("SYSTEM & DIAGNOSTICS"))
        root.addView(button("Playback status & diagnostics"){showPlaybackDiagnostics()})
        root.addView(button("View diagnostic logs"){showDiagnosticLogs()})
        root.addView(button("Save diagnostic report"){exportDiagnosticReport()})
        root.addView(button("Test network speed"){runNetworkDiagnostics()})
        root.addView(button("Sleep timer"){sleepTimer()})
        root.addView(button("Favorites"){favorites()})
        root.addView(button("Hidden channels"){hiddenChannels()})
        root.addView(settingsToggle("Check hidden channels automatically","auto_hidden_channel_check",true))
        root.addView(label("Checks hidden channels after startup without delaying playback."))
        root.addView(settingsToggle("Check hidden channels in background","background_hidden_channel_check",true))
        root.addView(label("When you leave HomeTV after playback, a low-priority check continues while the app process remains alive. Results are saved and shown when you return. Android may stop background work if it kills the app."))
        root.addView(settingsToggle("Automatically restore working channels","auto_unhide_working_channels",false))
        root.addView(label("OFF by default. When ON, a channel is restored only after actual playback succeeds. URL checks alone never unhide channels."))
        root.addView(button("TV guide (EPG)"){configureEpg()})
        root.addView(button("TV guide time adjustment"){configureEpgOffset()})
        root.addView(button("User profile: "+(if(viewerId()=="default")"Default" else viewerId())){manageViewerProfiles()})
        root.addView(tvSection("PLAYBACK & PICTURE"))
        root.addView(button("Info panel opacity: "+prefs.getInt("information_opacity",65)+"%"){chooseInformationTransparency()})
        root.addView(settingsToggle("Show programme information in channel details","show_channel_epg",true))
        root.addView(button("Playback engine: "+(prefs.getString("engine","Media3")?:"Media3")){
            val choices=arrayOf("Auto","Media3","LibVLC")
            AlertDialog.Builder(this).setTitle("Playback engine").setSingleChoiceItems(choices,choices.indexOf(prefs.getString("engine","Media3"))){dialog,i->
                prefs.edit().putString("engine",choices[i]).apply()
                dialog.dismiss();settings()
            }.setNegativeButton("Cancel",null).show()
        })
        root.addView(settingsToggle("Live preview while browsing channels","live_channel_preview",false))
        root.addView(button("Buffering profile: "+bufferProfile()){chooseBufferProfile()})
        root.addView(label("Ultra Fast = quickest switching; Balanced = recommended; Stable / Maximum Buffer = fewer pauses on uneven networks. Changes apply to the next channel."))
        root.addView(settingsToggle("Check alternative stream links in background","background_url_checks",false){startBackgroundChannelChecks()})
        root.addView(label("YouTube: Native HLS first when publicly available; WebView fallback for other videos."))
        root.addView(settingsToggle("Skip unavailable channels automatically","auto_skip_nonworking",false))
        root.addView(label("OFF by default: stay on a channel during interruptions. Turn ON only if you want HomeTV to move automatically after all URLs and both players fail."))
        root.addView(settingsToggle("Reconnect interrupted streams","auto_reconnect",true))
        root.addView(tvSection("PARENTAL & ACCESS CONTROL"))
        root.addView(button("Manage protected channels & groups"){manageLockedChannels()})
        root.addView(button("Manage channel-surfing exclusions"){manageSkippedChannels()})
        root.addView(button("Set / change parental PIN"){
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
        root.addView(settingsToggle("Resume previous channel","resume",true))
        root.addView(button("Startup screen: "+(prefs.getString("launch_screen","Last channel")?:"Last channel")){
            val choices=arrayOf("Home","Last channel","Channels")
            AlertDialog.Builder(this).setTitle("Choose startup screen").setSingleChoiceItems(choices,choices.indexOf(prefs.getString("launch_screen","Last channel"))){dialog,i->
                prefs.edit().putString("launch_screen",choices[i]).apply();dialog.dismiss();settings()
            }.setNegativeButton("Cancel",null).show()
        })
        root.addView(settingsToggle("Attempt launch at device startup","boot",false))
        root.addView(label("Android 10 and later restrict automatic app launches after boot. On those TVs, open HomeTV manually."))
        root.addView(label("Auto-start at TV power-on is restricted on Android 10+ and depends on TV manufacturer settings. Enabling this does not guarantee launch."))
        root.addView(button("Default stream quality: "+(prefs.getString("quality_global","Auto") ?: "Auto")){selectQuality()})
        root.addView(label("Auto uses the original quality offered by the active playback engine/stream. Manual resolution preferences apply only where the engine and stream support them."))
        root.addView(button("Default screen fit: "+(prefs.getString("screen_mode","16:9") ?: "Fit")){selectScreenMode()})
        root.addView(button("Clock display"){clockSettings()})
        root.addView(label("Remote: Up previews next channel; Down previews previous channel; OK plays selected channel; Left opens channels; Right/Menu opens playback settings."))
        root.addView(button("Home"){showHome()})
        // Number actionable rows (including toggles) so TV-remote users can
        // identify menu entries consistently while scrolling the settings page.
        var settingsActionNumber=0
        for(position in 0 until root.childCount){
            val item=root.getChildAt(position)
            if(item is Button){
                settingsActionNumber++
                item.text="%02d   %s".format(
                    java.util.Locale.ROOT,settingsActionNumber,item.text.toString()
                )
            }
        }
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
        // Fullscreen WebView/HTML5 video can swallow the remote BACK key.
        if(active>=0 && event.keyCode==KeyEvent.KEYCODE_BACK){
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0)onBackPressed()
            return true
        }
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
                // Live streams may expose a seekable DVR window. Only consume
                // LEFT/RIGHT when the active stream actually supports seeking.
                if(media=="live" && (event.keyCode==KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)){
                    val exo=player
                    val vlc=vlcPlayer
                    if(exo?.isCurrentMediaItemSeekable==true || vlc?.isSeekable==true){
                        val delta=if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)10_000L else -10_000L
                        if(exo?.isCurrentMediaItemSeekable==true){
                            val target=(exo.currentPosition+delta).coerceAtLeast(0L)
                            exo.seekTo(if(exo.duration>0)target.coerceAtMost(exo.duration) else target)
                        }else if(vlc?.isSeekable==true){
                            val target=(vlc.time+delta).coerceAtLeast(0L)
                            vlc.time=if(vlc.length>0)target.coerceAtMost(vlc.length) else target
                        }
                        return true
                    }
                }
                if(media=="live" && event.keyCode in setOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE)){
                    when(event.keyCode){
                        KeyEvent.KEYCODE_MEDIA_PLAY->{player?.play();vlcPlayer?.play()}
                        KeyEvent.KEYCODE_MEDIA_PAUSE->{player?.pause();vlcPlayer?.pause()}
                        else->{
                            player?.let{if(it.isPlaying)it.pause() else it.play()}
                            vlcPlayer?.let{if(it.isPlaying)it.pause() else it.play()}
                        }
                    }
                    return true
                }
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
                        KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT->{
                            val delta=if(event.keyCode==KeyEvent.KEYCODE_DPAD_RIGHT)10_000L else -10_000L
                            val exo=player
                            val vlc=vlcPlayer
                            if(exo!=null && exo.isCurrentMediaItemSeekable){
                                val target=(exo.currentPosition+delta).coerceAtLeast(0L)
                                exo.seekTo(if(exo.duration>0)target.coerceAtMost(exo.duration) else target)
                            }else if(vlc!=null && vlc.isSeekable){
                                val target=(vlc.time+delta).coerceAtLeast(0L)
                                vlc.time=if(vlc.length>0)target.coerceAtMost(vlc.length) else target
                            }else Toast.makeText(this,"Seeking unavailable for this stream",Toast.LENGTH_SHORT).show()
                            return true
                        }
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
                    KeyEvent.KEYCODE_BACK->{if(previewIndex>=0){clearChannelPreview();return true};onBackPressed();return true}
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onBackPressed(){
        if(!licenseReady){confirmExit();return}
        // Navigate back to the screen that opened Settings, never exit here.
        if(settingsScreenVisible){
            val previous=if(navigationHistory.isNotEmpty())navigationHistory.removeAt(navigationHistory.lastIndex) else ({showHome()})
            navigatingBack=true
            try{previous()}finally{navigatingBack=false}
            return
        }
        if(active>=0){
            // Cancel a pending unknown-URL probe on remote BACK.
            httpRouteGeneration++
            val screen=videoScreen
            if(web!=null && webTvDirectoryEntries.isNotEmpty() &&
                screen!=null && channels.getOrNull(active)?.mediaType=="web"){
                showWebTvDirectory(web!!,active,screen)
                return
            }
            val drawer=screen?.findViewWithTag<View>("channel_drawer")
                ?:screen?.findViewWithTag<View>("settings_drawer")
                ?:screen?.findViewWithTag<View>("left_navigation")
            if(drawer!=null && screen!=null){
                screen.removeView(drawer)
                screen.requestFocus()
                return
            }
            // Full-screen playback Back confirms exiting the app.
            // Open navigation drawers are closed by the branch above.
            confirmExit()
        }else{
            // BACK from Home offers exit; nested menus still return Home.
            if(homeScreenVisible)confirmExit() else showHome()
        }
    }
    private fun stopPlayback(){
        // A pending HTTP classification must never restart playback after BACK,
        // channel exit, or switching to the Home screen.
        httpRouteGeneration++
        val preserveYoutubeRoute=youtubeNativeTransition
        youtubeNativeTransition=false
        if(!preserveYoutubeRoute){
            youtubeNativeGeneration++
            youtubeNativeAttemptedUrl=null
            youtubeNativeStreamUrl=null
        }
        saveCurrentMediaProgress()
        networkRecoveryTask?.let{handler.removeCallbacks(it)}
        networkRecoveryTask=null
        closeVideoPreview()
        playbackGeneration++
        startupWatchdog?.let{handler.removeCallbacks(it)}
        startupWatchdog=null
        previewIndex=-1
        youtubeResolveTimeout?.let{handler.removeCallbacks(it)}
        youtubeResolveTimeout=null
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
        player?.release();player=null
        activeChannelGainProcessor=null
        vlcPlayer?.let{v->
            try{v.stop();v.vlcVout.detachViews();v.release()}catch(_:Exception){}
        }
        vlcPlayer=null
        vlcCore?.release();vlcCore=null
        web?.onPause();web?.destroy();web=null
        webTvDirectoryUrl=null
        webTvDirectoryEntries=emptyList()
        active=-1
    }
    override fun onTrimMemory(level:Int){
        super.onTrimMemory(level)
        if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)posterCache.evictAll()
    }
    override fun onDestroy(){
        epgTuneTask?.let{handler.removeCallbacks(it)};epgTuneTask=null
        sleepTimerTask?.let{handler.removeCallbacks(it)};sleepTimerTask=null
        stopVlcProbe()
        backgroundProbeGeneration++
        refreshGeneration++
        periodicProbeTask?.let{handler.removeCallbacks(it)}
        probePlayers.toList().forEach{try{it.release()}catch(_:Exception){}}
        probePlayers.clear()
        stopPlayback()
        // Drop in-memory caches and queued UI work on final exit. Keep the
        // persistent playlist files, preferences and validated license cache.
        handler.removeCallbacksAndMessages(null)
        posterCache.evictAll()
        posterLoading.clear()
        urlRouteCache.clear()
        engineAttempts.clear()
        recoveryAttempts.clear()
        unavailableChannels.clear()
        playedThisSession.clear()
        failedUrlsThisSession.clear()
        initialTried.clear()
        alternativeAttempted.clear()
        if(isFinishing)HomeTvDiagnostics.markCleanExit(this)
        unregisterLicenseNetworkCallback()
        super.onDestroy()
    }
}
