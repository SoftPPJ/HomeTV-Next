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

data class Channel(val name:String, val url:String, val group:String, val logo:String="", val source:String="", val headers:Map<String,String> = emptyMap())
data class Playlist(val name:String, val url:String, val enabled:Boolean=true)

class MainActivity: Activity() {
    private val prefs by lazy { getSharedPreferences("hometv", MODE_PRIVATE) }
    private var channels = mutableListOf<Channel>()
    private var player:ExoPlayer?=null
    private var vlcCore:LibVLC?=null
    private var vlcPlayer:VlcPlayer?=null
    private var vlcAttempted=false
    private var engineChannelUrl:String?=null
    private var web:WebView?=null
    private var active=-1
    private lateinit var root:LinearLayout
    private val lockIds get() = prefs.getStringSet("locked_channels", emptySet()) ?: emptySet()
    private val lockGroups get() = prefs.getStringSet("locked_groups", emptySet()) ?: emptySet()
    private var unlockedChannel:String?=null
    private var videoScreen:FrameLayout?=null
    private var currentPlayerView:PlayerView?=null
    private val handler=Handler(Looper.getMainLooper())
    private var banner:TextView?=null
    private var bannerTask:Runnable?=null
    private var fallbackAttempted=mutableSetOf<String>()
    private val favoriteIds get()=prefs.getStringSet("favorites",emptySet()) ?: emptySet()
    private var retryCount=0
    private var retryUrl:String?=null
    private var retryTask:Runnable?=null
    private fun favorite(c:Channel){
        val set=favoriteIds.toMutableSet()
        if(!set.add(id(c)))set.remove(id(c))
        prefs.edit().putStringSet("favorites",set).apply()
        Toast.makeText(this,if(set.contains(id(c)))"Added to Favorites" else "Removed from Favorites",Toast.LENGTH_SHORT).show()
    }
    private fun allChannels(){
        if(channels.isEmpty()){
            channelCollection("All channels",emptyList())
            if(playlists().any{it.enabled})refresh{channelCollection("All channels",channels.indices.toList())}
            return
        }
        channelCollection("All channels",channels.indices.toList())
    }
    private fun favorites(){channelCollection("Favorites",channels.indices.filter{favoriteIds.contains(id(channels[it]))})}
    private fun channelCollection(title:String,indices:List<Int>){
        reset(title+" ("+indices.size+")")
        val list=ListView(this).apply{
            dividerHeight=1
            isFocusable=true
            isFocusableInTouchMode=true
            setBackgroundColor(0xFF101E31.toInt())
        }
        val names=indices.map{i->
            val c=channels[i]
            (if(favoriteIds.contains(id(c)))"★ " else "")+c.name+"  ·  "+c.group
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
        list.setOnItemClickListener{_,_,position,_->playIndex(indices[position])}
        list.setOnItemLongClickListener{_,_,position,_->channelOptions(indices[position]);true}
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
        root.addView(label("Select a channel · Long press for options"))
        root.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(button("Back"){showHome()})
    }
    private val bufferProfiles=arrayOf("Ultra Fast","Fast","Balanced","Stable","Maximum Buffer")
    private fun bufferProfile()=prefs.getString("buffer_profile","Balanced") ?: "Balanced"
    private fun bufferValues():IntArray=when(bufferProfile()){
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
                dialog.dismiss();settings()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun engineFor(c:Channel):String=prefs.getString("engine_"+id(c),null) ?: (prefs.getString("engine","Auto") ?: "Auto")
    private fun chooseChannelEngine(i:Int){
        if(i !in channels.indices)return
        val c=channels[i]
        val choices=arrayOf("Auto","Media3","LibVLC")
        AlertDialog.Builder(this).setTitle(c.name+" — Player")
            .setSingleChoiceItems(choices,choices.indexOf(engineFor(c)).coerceAtLeast(0)){dialog,which->
                prefs.edit().putString("engine_"+id(c),choices[which]).apply()
                dialog.dismiss()
                if(active==i)playIndex(i)
            }.setNegativeButton("Cancel",null).show()
    }
    private fun channelOptions(i:Int){
        if(i !in channels.indices)return
        val c=channels[i]
        val opts=arrayOf("Watch","Favorite / Unfavorite","Lock / Unlock","Screen size (this channel)","All channels","Settings","Exit","Audio tracks","Playback engine","Picture quality (this channel)")
        AlertDialog.Builder(this).setTitle(c.name).setItems(opts){_,v->
            when(v){
                0->playIndex(i)
                1->{favorite(c);if(active<0)allChannels()}
                2->toggleLock(id(c),false)
                3->selectScreenMode(i)
                4->allChannels()
                5->settings()
                6->confirmExit()
                7->audioTracks()
                8->chooseChannelEngine(i)
                9->selectQuality(i)
            }
        }.show()
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(14,12,14,12) }
        root.setBackgroundColor(0xFF101E31.toInt())
        setContentView(root)
        channels=cachedChannels()
        // Start directly in the last channel; do not briefly display Home first.
        if(channels.isNotEmpty()){
            resumeLast()
        }else{
            refresh{
                if(channels.isNotEmpty())resumeLast() else showHome()
            }
        }
    }
    private fun label(s:String)=TextView(this).apply { text=s; textSize=23f; setPadding(18,16,18,16); setTextColor(android.graphics.Color.WHITE) }
    private fun button(s:String, action:()->Unit)=Button(this).apply {
        text=s;isAllCaps=false;textSize=17f
        setTextColor(android.graphics.Color.WHITE)
        backgroundTintList=android.content.res.ColorStateList.valueOf(0xFF254466.toInt())
        setOnClickListener{action()}
    }
    private fun reset(title:String) { stopPlayback(); window.decorView.systemUiVisibility=0; setContentView(root); root.removeAllViews(); root.addView(label(title)) }
    private fun playlists():MutableList<Playlist> {
        val arr=JSONArray(prefs.getString("playlists","[]"))
        return MutableList(arr.length()){ val o=arr.getJSONObject(it); Playlist(o.getString("name"),o.getString("url"),o.optBoolean("enabled",true)) }
    }
    private fun savePlaylists(list:List<Playlist>) {
        val arr=JSONArray(); list.forEach { arr.put(JSONObject().put("name",it.name).put("url",it.url).put("enabled",it.enabled)) }
        prefs.edit().putString("playlists",arr.toString()).apply()
    }
    private fun showHome() {
        reset("HomeTV  •  LIVE")
        root.addView(label("YOUR TELEVISION  |  "+channels.size+" CHANNELS"))
        root.addView(button("▶  Continue watching"){if(channels.isEmpty())refresh{resumeLast()}else resumeLast()})
        root.addView(label("DISCOVER"))
        root.addView(button("▦  All live channels"){allChannels()})
        root.addView(button("★  My favorites"){favorites()})
        root.addView(button("☷  Browse by category"){showChannels()})
        root.addView(label("LIBRARY & CONTROLS"))
        root.addView(button("↻  Refresh channel lists"){refresh{showHome()}})
        root.addView(button("＋  Manage playlists"){managePlaylists()})
        root.addView(button("⚙  Player & TV settings"){settings()})
        root.addView(button("⏻  Exit HomeTV"){confirmExit()})
    }
    private fun managePlaylists(){
        reset("Playlists")
        val list=playlists()
        list.forEachIndexed { index,p ->
            root.addView(button((if(p.enabled)"✓ " else "○ ")+p.name+" — "+p.url.take(40)){
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
        root.addView(button("Add M3U playlist"){editPlaylist(null)})
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
                refresh{managePlaylists()}
            } else Toast.makeText(this,"Enter name and HTTP(S) URL",Toast.LENGTH_LONG).show()
        }.setNegativeButton("Cancel",null).show()
    }
    private fun cacheFile(p:Playlist):java.io.File{
        val digest=MessageDigest.getInstance("SHA-256").digest(p.url.toByteArray())
            .joinToString(""){"%02x".format(it)}
        return java.io.File(filesDir,"playlist_"+digest+".m3u")
    }
    private fun cachedChannels():MutableList<Channel>{
        val found=mutableListOf<Channel>()
        playlists().filter{it.enabled}.forEach{p->
            try{
                val file=cacheFile(p)
                if(file.exists())found.addAll(parseM3u(file.readText(),p.name))
            }catch(e:Exception){android.util.Log.w("HomeTV","Playlist cache read failed",e)}
        }
        return found.distinctBy{it.name.lowercase()+"|"+it.url}.toMutableList()
    }
    private fun refresh(done:()->Unit){
        val sources=playlists().filter{it.enabled}
        if(sources.isEmpty()){channels.clear();done();return}
        thread {
            val results=Array(sources.size){emptyList<Channel>()}
            val workers=sources.mapIndexed{index,p->
                thread {
                    try{
                        val conn=URL(p.url).openConnection().apply{
                            connectTimeout=5000
                            readTimeout=7000
                        }
                        val body=conn.getInputStream().bufferedReader().use{it.readText()}
                        val parsed=parseM3u(body,p.name)
                        if(parsed.isNotEmpty()){
                            results[index]=parsed
                            val file=cacheFile(p)
                            val temp=java.io.File(file.absolutePath+".tmp")
                            temp.writeText(body)
                            if(!temp.renameTo(file)){
                                temp.copyTo(file,overwrite=true)
                                temp.delete()
                            }
                        }
                    }catch(e:Exception){
                        android.util.Log.w("HomeTV","Playlist download failed: "+p.name,e)
                    }
                    if(results[index].isEmpty()){
                        try{
                            val file=cacheFile(p)
                            if(file.exists())results[index]=parseM3u(file.readText(),p.name)
                        }catch(e:Exception){android.util.Log.w("HomeTV","Cache fallback failed",e)}
                    }
                }
            }
            workers.forEach{it.join()}
            val found=results.flatMap{it}.distinctBy{it.name.lowercase()+"|"+it.url}
            runOnUiThread{
                channels=found.toMutableList()
                done()
            }
        }
    }
    private fun parseM3u(body:String,source:String):List<Channel>{
        val out=mutableListOf<Channel>()
        var name="";var group="Other";var logo=""
        var headers=mutableMapOf<String,String>()
        fun attr(line:String,key:String)=Regex(Regex.escape(key)+"""="([^"]*)" """.trim()).find(line)?.groupValues?.get(1)?:""
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
                    out.add(Channel(name,url,group,logo,source,localHeaders))
                }
            }
        }
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
        reset("Channels (${channels.size})")
        root.addView(button("Refresh"){refresh{showChannels()}})
        val groups=channels.map{it.group}.distinct()
        val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        groups.forEach { group->
            list.addView(button("▸ $group"){showGroup(group)})
        }
        scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(button("Home"){showHome()})
    }
    private fun showGroup(group:String){
        reset(group)
        root.addView(button(if(lockGroups.contains(group))"Unlock group settings" else "Lock group settings"){toggleLock(group,true)})
        val scroll=ScrollView(this);val list=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        channels.forEachIndexed { i,c->if(c.group==group){
            list.addView(button((if(favoriteIds.contains(id(c)))"★ " else "☆ ")+c.name){playIndex(i)})
            list.addView(button("⋮ Options"){channelOptions(i)})
            list.addView(button(if(lockIds.contains(id(c)))"🔒 Unlock channel" else "Lock channel"){toggleLock(id(c),false)})
        }}
        scroll.addView(list);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(button("Groups"){showChannels()})
    }
    private fun id(c:Channel)=c.source+"|"+c.name+"|"+c.url
    private fun toggleLock(value:String,group:Boolean){
        requirePin {
            val key=if(group)"locked_groups" else "locked_channels"
            val set=(prefs.getStringSet(key,emptySet())?:emptySet()).toMutableSet()
            if(!set.add(value))set.remove(value)
            prefs.edit().putStringSet(key,set).apply()
            if(group)showGroup(value) else showGroup(channels.firstOrNull{id(it)==value}?.group?:"Other")
        }
    }
    private fun hash(pin:String)=MessageDigest.getInstance("SHA-256").digest(pin.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun requirePin(action:()->Unit){
        val saved=prefs.getString("pin",null)
        if(saved==null){Toast.makeText(this,"Set a PIN in Settings first",Toast.LENGTH_LONG).show();return}
        val field=EditText(this).apply{inputType=2;hint="PIN"}
        AlertDialog.Builder(this).setTitle("Enter PIN").setView(field).setPositiveButton("Unlock"){_,_->
            if(hash(field.text.toString())==saved)action() else Toast.makeText(this,"Incorrect PIN",Toast.LENGTH_SHORT).show()
        }.setNegativeButton("Cancel",null).show()
    }
    private fun showBanner(message:String, error:Boolean=false){
        val screen=videoScreen ?: return
        bannerTask?.let{handler.removeCallbacks(it)}
        banner?.let{screen.removeView(it)}
        val text=TextView(this).apply {
            this.text=message
            textSize=20f
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(if(error) 0xDD842020.toInt() else 0xDD15283C.toInt())
            setPadding(24,18,24,18)
        }
        banner=text
        screen.addView(text,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))
        if(!error){
            val task=Runnable{if(banner===text){screen.removeView(text);banner=null}}
            bannerTask=task
            handler.postDelayed(task,2500)
        }
    }
    private fun confirmExit(){
        AlertDialog.Builder(this).setTitle("Exit HomeTV?")
            .setMessage("Stop playback and close the app?")
            .setPositiveButton("Exit"){_,_->stopPlayback();finishAffinity()}
            .setNegativeButton("Cancel",null).show()
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
    private fun showChannelOptions(){
        if(active !in channels.indices)return
        val channel=channels[active]
        val options=arrayOf("Channel list","Previous channel","Next channel","Fit / Fill screen","Lock / unlock channel","Settings","Exit HomeTV","Favorite / Unfavorite","All channels","Audio tracks","Playback engine")
        AlertDialog.Builder(this).setTitle(channel.name+" — Channel settings")
            .setItems(options){_,index->
                when(index){
                    0->showOverlayList()
                    1->switchChannel(-1)
                    2->switchChannel(1)
                    3->toggleScreenMode()
                    4->toggleLock(id(channel),false)
                    5->settings()
                    6->confirmExit()
                    7->favorite(channel)
                    8->allChannels()
                    9->audioTracks()
                    10->chooseChannelEngine(active)
                }
            }.setNegativeButton("Close",null).show()
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
    private var previewIndex=-1
    private var previewTask:Runnable?=null
    private fun previewChannel(delta:Int){
        if(channels.isEmpty())return
        previewTask?.let{handler.removeCallbacks(it)}
        previewIndex=((if(previewIndex>=0)previewIndex else active)+delta+channels.size)%channels.size
        showBanner("▶ "+(previewIndex+1)+"  "+channels[previewIndex].name+"   •   OK to play")
        val task=Runnable{previewIndex=-1}
        previewTask=task
        handler.postDelayed(task,8000)
    }
    private fun confirmPreview(){
        val target=previewIndex
        previewIndex=-1
        previewTask?.let{handler.removeCallbacks(it)}
        previewTask=null
        if(target in channels.indices)playIndex(target) else showBanner("Use ▲ / ▼ to preview channels")
    }
    private fun showOverlayList(){
        val screen=videoScreen ?:return
        if(channels.isEmpty())return
        screen.findViewWithTag<View>("channel_drawer")?.let{screen.removeView(it);return}
        screen.findViewWithTag<View>("settings_drawer")?.let{screen.removeView(it)}
        val drawer=LinearLayout(this).apply{
            tag="channel_drawer"
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(0xF015283C.toInt())
            isFocusableInTouchMode=true
        }
        drawer.addView(TextView(this).apply{
            text="CHANNELS  •  OK to play"
            textSize=19f
            setTextColor(android.graphics.Color.WHITE)
            setPadding(16,16,8,12)
        })
        val list=ListView(this)
        val names=channels.mapIndexed{i,c->(i+1).toString()+". "+c.name}
        list.adapter=android.widget.ArrayAdapter<String>(this,android.R.layout.simple_list_item_1,names)
        drawer.addView(list,LinearLayout.LayoutParams(-1,0,1f))
        val width=(resources.displayMetrics.widthPixels*0.43f).toInt().coerceAtLeast(260)
        screen.addView(drawer,FrameLayout.LayoutParams(width,-1,Gravity.START))
        fun close(){if(drawer.parent===screen)screen.removeView(drawer);screen.requestFocus()}
        list.setOnItemClickListener{_,_,position,_->close();playIndex(position)}
        list.setOnKeyListener{_,code,event->
            if(event.action==KeyEvent.ACTION_DOWN && (code==KeyEvent.KEYCODE_BACK || code==KeyEvent.KEYCODE_DPAD_RIGHT)){
                close();true
            }else false
        }
        list.setSelection(active.coerceAtLeast(0))
        list.requestFocus()
    }
    private fun showPlaybackSettings(){
        val screen=videoScreen ?:return
        screen.findViewWithTag<View>("settings_drawer")?.let{screen.removeView(it);return}
        screen.findViewWithTag<View>("channel_drawer")?.let{screen.removeView(it)}
        val options=arrayOf(
            "Universal aspect ratio: "+(prefs.getString("screen_mode","Fit") ?: "Fit"),
            "This channel aspect ratio: "+screenMode(),
            "Picture quality","Buffering","Audio tracks","Playback engine"
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
                5->if(active>=0)chooseChannelEngine(active)
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
        if(mode=="Best Available")builder.setForceHighestSupportedBitrate(true)
        else builder.setForceHighestSupportedBitrate(false)
        exo.trackSelectionParameters=builder.build()
    }
    private fun selectQuality(index:Int?=null){
        val url=index?.let{channels.getOrNull(it)?.url}
        val key=url?.let{"quality_channel_"+hash(it)}
        val options=if(key!=null)arrayOf("Use universal default")+qualityModes else qualityModes
        val current=if(key!=null)prefs.getString(key,null) else prefs.getString("quality_global","Auto")
        val selected=if(key!=null && current==null)0 else options.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(if(index!=null)"Picture quality · "+channels[index].name else "Universal picture quality")
            .setSingleChoiceItems(options,selected){dialog,choice->
                val editor=prefs.edit()
                if(key!=null){
                    if(choice==0)editor.remove(key) else editor.putString(key,options[choice])
                }else editor.putString("quality_global",options[choice])
                editor.apply()
                applyQuality()
                dialog.dismiss()
                if(active>=0)showBanner("Quality: "+qualityMode()) else if(index==null)settings()
            }.setNegativeButton("Cancel",null).show()
    }
    private val screenModes=arrayOf("Fit","Fill / Crop","Stretch","16:9","4:3","Original","Zoom 110%","Zoom 125%","Zoom 150%")
    private fun screenMode():String{
        val url=channels.getOrNull(active)?.url
        if(url!=null){
            val saved=prefs.getString("screen_channel_"+hash(url),null)
            if(saved!=null)return saved
        }
        return prefs.getString("screen_mode","Fit") ?: "Fit"
    }
    private fun resizeMode():Int=when(screenMode()){
        "Fit","16:9","4:3"->androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        "Stretch"->androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
        "Original"->androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        else->androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }
    private fun applyVideoSize(){
        val mode=screenMode()
        currentPlayerView?.let{view->
            view.resizeMode=resizeMode()
            // Zoom the video surface, not the entire player view and its controls.
            val zoom=when(mode){
                "Zoom 110%"->1.1f
                "Zoom 125%"->1.25f
                "Zoom 150%"->1.5f
                else->1f
            }
            val surface=view.videoSurfaceView
            if(surface!=null){
                surface.scaleX=zoom
                surface.scaleY=zoom
            }
            val ratio=when(mode){
                "16:9"->16f/9f
                "4:3"->4f/3f
                else->0f
            }
            if(ratio>0f){
                // Respect selected aspect ratio independently of stream metadata.
                val frame=view.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                frame?.setAspectRatio(ratio)
            }else{
                val frame=view.findViewById<androidx.media3.ui.AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                if(frame!=null){
                    val format=player?.videoFormat
                    val videoRatio=if(format!=null && format.height>0)format.width.toFloat()*format.pixelWidthHeightRatio/format.height else 0f
                    frame.setAspectRatio(videoRatio)
                }
            }
        }
        vlcPlayer?.let{vlc->
            try{
                vlc.setScale(0f)
                vlc.setAspectRatio(when(mode){
                    "16:9","Stretch"->"16:9"
                    "4:3"->"4:3"
                    else->null
                })
                val scale=when(mode){
                    "Fill / Crop"->1.35f
                    "Zoom 110%"->1.1f
                    "Zoom 125%"->1.25f
                    "Zoom 150%"->1.5f
                    "Original"->1f
                    else->0f
                }
                if(scale>0f)vlc.setScale(scale)
            }catch(e:Exception){android.util.Log.w("HomeTV","Video size adjustment",e)}
        }
    }
    private fun selectScreenMode(channelIndex:Int?=null){
        val current=if(channelIndex!=null){
            prefs.getString("screen_channel_"+hash(channels[channelIndex].url),null)
                ?:prefs.getString("screen_mode","Fit") ?: "Fit"
        }else prefs.getString("screen_mode","Fit") ?: "Fit"
        val labels=if(channelIndex!=null)arrayOf("Use universal default")+screenModes else screenModes
        val selected=if(channelIndex!=null && !prefs.contains("screen_channel_"+hash(channels[channelIndex].url)))0
            else labels.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle(if(channelIndex!=null)"Screen size · "+channels[channelIndex].name else "Universal screen size")
            .setSingleChoiceItems(labels,selected){dialog,i->
                val edit=prefs.edit()
                if(channelIndex!=null){
                    val key="screen_channel_"+hash(channels[channelIndex].url)
                    if(i==0)edit.remove(key) else edit.putString(key,labels[i])
                }else edit.putString("screen_mode",labels[i])
                edit.apply()
                dialog.dismiss()
                applyVideoSize()
                if(active>=0)showBanner("Screen: "+screenMode()) else if(channelIndex==null)settings()
            }.setNegativeButton("Cancel",null).show()
    }
    private fun toggleScreenMode(){
        val next=if(screenMode()=="Fill / Crop")"Fit" else "Fill / Crop"
        val url=channels.getOrNull(active)?.url
        if(url!=null)prefs.edit().putString("screen_channel_"+hash(url),next).apply()
        else prefs.edit().putString("screen_mode",next).apply()
        applyVideoSize()
        if(active>=0)showBanner("Screen: "+next)
    }
    private fun playIndex(index:Int){
        if(index !in channels.indices)return
        val c=channels[index]
        if(unlockedChannel!=id(c) && (lockIds.contains(id(c)) || lockGroups.contains(c.group))){
            requirePin {unlockedChannel=id(c);playIndex(index)}
            return
        }
        if(engineChannelUrl!=c.url){vlcAttempted=false;engineChannelUrl=c.url}
        if(retryUrl!=c.url)retryCount=0
        retryUrl=c.url
        stopPlayback()
        active=index
        if(unlockedChannel!=id(c)) unlockedChannel=null
        prefs.edit().putString("last_url",c.url).apply()
        window.decorView.systemUiVisibility=(View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        val screen=FrameLayout(this).apply{setBackgroundColor(android.graphics.Color.BLACK);isFocusableInTouchMode=true}
        videoScreen=screen
        setContentView(screen)
        val videoId=Regex("""(?:youtube\.com/(?:live/|watch\?v=|embed/)|youtu\.be/)([A-Za-z0-9_-]{11})""").find(c.url)?.groupValues?.get(1)
        if(videoId!=null){
            web=WebView(this).apply {
                setBackgroundColor(android.graphics.Color.BLACK)
                settings.javaScriptEnabled=true
                settings.domStorageEnabled=true
                webChromeClient=WebChromeClient()
                loadDataWithBaseURL("https://www.youtube.com", "<html><head><meta name='viewport' content='width=device-width, initial-scale=1'></head><body style='margin:0;background:black;overflow:hidden'><iframe style='position:absolute;top:0;left:0;width:100%;height:100%;border:0' src='https://www.youtube.com/embed/$videoId?autoplay=1' allow='autoplay; encrypted-media; fullscreen' allowfullscreen></iframe></body></html>","text/html","UTF-8",null)
            }
            screen.addView(web,FrameLayout.LayoutParams(-1,-1))
        }else if(engineFor(c)=="LibVLC"){
            startVlc(c,index,screen)
        }else{
            val factory=DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(20000)
                .setUserAgent(c.headers["User-Agent"] ?: "VLC/3.0.20 LibVLC/3.0.20")
                .setDefaultRequestProperties(c.headers.filterKeys{!it.equals("User-Agent",true)})
            val buffer=bufferValues()
            val loadControl=DefaultLoadControl.Builder()
                .setBufferDurationsMs(buffer[0],buffer[1],buffer[2],buffer[3]).build()
            val exo=ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).setLoadControl(loadControl).build()
            exo.setAudioAttributes(AudioAttributes.DEFAULT,true)
            exo.volume=1f
            player=exo
            applyQuality()
            val view=PlayerView(this).apply{
                player=exo
                useController=false
                resizeMode=resizeMode()
                setBackgroundColor(android.graphics.Color.BLACK)
            }
            currentPlayerView=view
            screen.addView(view,FrameLayout.LayoutParams(-1,-1))
            exo.addListener(object:Player.Listener{
                override fun onPlaybackStateChanged(state:Int){
                    if(state==Player.STATE_READY){
                        retryCount=0
                        retryTask?.let{handler.removeCallbacks(it)}
                        retryTask=null
                    }
                }
                override fun onPlayerError(error:PlaybackException){
                    val httpStatus=(error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
                    android.util.Log.e("HomeTV", "Playback error for "+c.name+" HTTP="+httpStatus, error)
                    if(engineFor(c)=="Auto" && !vlcAttempted){
                        vlcAttempted=true
                        handler.post{
                            if(active==index && videoScreen===screen){
                                player?.release();player=null
                                currentPlayerView?.let{screen.removeView(it)}
                                currentPlayerView=null
                                showBanner("Trying LibVLC for "+c.name)
                                startVlc(c,index,screen)
                            }
                        }
                        return
                    }
                    val alternative=channels.indexOfFirst{other->other.name.equals(c.name,true) && other.url!=c.url && !fallbackAttempted.contains(other.url)}
                    if(alternative>=0){
                        fallbackAttempted.add(c.url)
                        showBanner("Trying another stream for ${c.name}")
                        handler.post{playIndex(alternative)}
                    }else if(prefs.getBoolean("auto_reconnect",true) && retryCount<2){
                        retryCount++
                        showBanner("Reconnecting "+c.name)
                        val task=Runnable{if(active==index)playIndex(index)}
                        retryTask=task
                        handler.postDelayed(task,2500L*retryCount)
                    }else showBanner("Cannot play ${c.name}: ${error.errorCodeName}"+(if(httpStatus!=null)" (HTTP "+httpStatus+")" else "")+". Press OK for channels.",true)
                }
            })
            try{
                exo.setMediaItem(MediaItem.fromUri(c.url))
                exo.prepare()
                exo.play()
            }catch(e:Exception){showBanner("Cannot play ${c.name}: ${e.message}",true)}
        }
        screen.requestFocus()
        showBanner("${index+1}  •  ${c.name}")
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
            vlc.setEventListener{event->
                if(event.type==VlcPlayer.Event.EncounteredError && active==index && vlcPlayer===vlc){
                    handler.post{
                        if(active==index && vlcPlayer===vlc){
                            showBanner("LibVLC could not play "+c.name+". Trying another source.",true)
                            val alternative=channels.indexOfFirst{it.name.equals(c.name,true) && it.url!=c.url && !fallbackAttempted.contains(it.url)}
                            if(alternative>=0){
                                fallbackAttempted.add(c.url)
                                playIndex(alternative)
                            }
                        }
                    }
                }
            }
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
        }
    }
    private fun switchChannel(delta:Int){if(channels.isNotEmpty())playIndex((active+delta+channels.size)%channels.size)}
    private fun resumeLast(){
        if(channels.isEmpty()){showHome();return}
        val url=prefs.getString("last_url",null)
        val index=channels.indexOfFirst{it.url==url}
        playIndex(if(index>=0)index else 0)
    }
    private fun settings(){
        reset("HomeTV Settings")
        root.addView(button("Manage playlists"){managePlaylists()})
        root.addView(button("Favorites"){favorites()})
        root.addView(button("Playback engine: "+(prefs.getString("engine","Auto")?:"Auto")){
            val choices=arrayOf("Auto","Media3","LibVLC")
            AlertDialog.Builder(this).setTitle("Playback engine").setSingleChoiceItems(choices,choices.indexOf(prefs.getString("engine","Auto"))){dialog,i->
                prefs.edit().putString("engine",choices[i]).apply()
                dialog.dismiss();settings()
            }.setNegativeButton("Cancel",null).show()
        })
        root.addView(button("Buffering: "+bufferProfile()){chooseBufferProfile()})
        root.addView(label("Ultra Fast = quickest switching; Balanced = recommended; Stable / Maximum Buffer = fewer pauses on uneven networks. Changes apply to the next channel."))
        root.addView(button("Auto-reconnect streams: "+if(prefs.getBoolean("auto_reconnect",true))"ON" else "OFF"){
            prefs.edit().putBoolean("auto_reconnect",!prefs.getBoolean("auto_reconnect",true)).apply();settings()
        })
        root.addView(button("Set / change parental PIN"){
            val field=EditText(this).apply{hint="New 4–6 digit PIN";inputType=2}
            val save={
                val value=field.text.toString()
                if(value.matches(Regex("[0-9]{4,6}"))){
                    prefs.edit().putString("pin",hash(value)).apply()
                    Toast.makeText(this,"PIN saved",Toast.LENGTH_SHORT).show()
                }else Toast.makeText(this,"Use 4–6 digits",Toast.LENGTH_LONG).show()
            }
            if(prefs.contains("pin"))requirePin{AlertDialog.Builder(this).setTitle("New PIN").setView(field).setPositiveButton("Save"){_,_->save()}.show()}
            else AlertDialog.Builder(this).setTitle("Set PIN").setView(field).setPositiveButton("Save"){_,_->save()}.show()
        })
        root.addView(button("Resume last channel: "+if(prefs.getBoolean("resume",true))"ON" else "OFF"){
            prefs.edit().putBoolean("resume",!prefs.getBoolean("resume",true)).apply();settings()
        })
        root.addView(button("Open on launch: "+(prefs.getString("launch_screen","Last channel")?:"Last channel")){
            val choices=arrayOf("Home","Last channel","Channels")
            AlertDialog.Builder(this).setTitle("When HomeTV opens").setSingleChoiceItems(choices,choices.indexOf(prefs.getString("launch_screen","Last channel"))){dialog,i->
                prefs.edit().putString("launch_screen",choices[i]).apply();dialog.dismiss();settings()
            }.setNegativeButton("Cancel",null).show()
        })
        root.addView(button("Try launch at device boot: "+if(prefs.getBoolean("boot",false))"ON" else "OFF"){
            prefs.edit().putBoolean("boot",!prefs.getBoolean("boot",false)).apply();settings()
        })
        root.addView(label("Auto-start at TV power-on is restricted on Android 10+ and depends on TV manufacturer settings. Enabling this does not guarantee launch."))
        root.addView(button("Universal picture quality: "+(prefs.getString("quality_global","Auto") ?: "Auto")){selectQuality()})
        root.addView(label("Picture quality controls available stream resolutions in Media3. LibVLC and fixed-quality streams may ignore this setting."))
        root.addView(button("Universal screen size: "+(prefs.getString("screen_mode","Fit") ?: "Fit")){selectScreenMode()})
        root.addView(label("Remote: Up/Down preview channel; OK plays highlighted channel; Left opens channel list; Right/Menu opens playback settings; Back returns."))
        root.addView(button("Home"){showHome()})
    }
    override fun dispatchKeyEvent(event:KeyEvent):Boolean{
        if(active>=0 && event.action==KeyEvent.ACTION_DOWN){
            // Dispatch before PlayerView/SurfaceView can consume remote DPAD events.
            // Let an open channel drawer handle its own list navigation.
            val drawerOpen=videoScreen?.findViewWithTag<View>("channel_drawer")!=null || videoScreen?.findViewWithTag<View>("settings_drawer")!=null
            if(!drawerOpen){
                when(event.keyCode){
                    KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_CHANNEL_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS->{previewChannel(-1);return true}
                    KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_CHANNEL_UP,
                    KeyEvent.KEYCODE_MEDIA_NEXT->{previewChannel(1);return true}
                    KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER->{confirmPreview();return true}
                    KeyEvent.KEYCODE_DPAD_LEFT->{showOverlayList();return true}
                    KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_MENU->{showPlaybackSettings();return true}
                    KeyEvent.KEYCODE_BACK->{onBackPressed();return true}
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onBackPressed(){
        // Back navigates one level; Back from Home exits the application.
        if(active>=0){
            unlockedChannel=null
            allChannels()
        }else if(root.childCount>0 && (root.getChildAt(0) as? TextView)?.text?.toString()?.startsWith("HomeTV")!=true){
            showHome()
        }else{
            confirmExit()
        }
    }
    private fun stopPlayback(){
        previewIndex=-1
        previewTask?.let{handler.removeCallbacks(it)}
        previewTask=null
        bannerTask?.let{handler.removeCallbacks(it)}
        retryTask?.let{handler.removeCallbacks(it)}
        retryTask=null
        bannerTask=null;banner=null;videoScreen=null;currentPlayerView=null
        player?.release();player=null
        vlcPlayer?.let{v->
            try{v.stop();v.vlcVout.detachViews();v.release()}catch(_:Exception){}
        }
        vlcPlayer=null
        vlcCore?.release();vlcCore=null
        web?.onPause();web?.destroy();web=null
        active=-1
    }
    override fun onDestroy(){stopPlayback();super.onDestroy()}
}
