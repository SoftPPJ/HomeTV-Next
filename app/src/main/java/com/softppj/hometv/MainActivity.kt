package com.softppj.hometv

import android.app.*
import android.os.*
import android.content.*
import android.view.*
import android.widget.*
import android.webkit.*
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

data class Channel(val name:String, val url:String, val group:String, val logo:String="", val source:String="")
data class Playlist(val name:String, val url:String, val enabled:Boolean=true)

class MainActivity: Activity() {
    private val prefs by lazy { getSharedPreferences("hometv", MODE_PRIVATE) }
    private var channels = mutableListOf<Channel>()
    private var player:ExoPlayer?=null
    private var web:WebView?=null
    private var active=-1
    private lateinit var root:LinearLayout
    private val lockIds get() = prefs.getStringSet("locked_channels", emptySet()) ?: emptySet()
    private val lockGroups get() = prefs.getStringSet("locked_groups", emptySet()) ?: emptySet()
    private var unlockedChannel:String?=null
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(14,12,14,12) }
        setContentView(root)
        showHome()
        if(prefs.getBoolean("resume",true)) refresh { resumeLast() }
    }
    private fun label(s:String)=TextView(this).apply { text=s; textSize=18f; setPadding(8,12,8,12); setTextColor(-1) }
    private fun button(s:String, action:()->Unit)=Button(this).apply { text=s; setOnClickListener{action()} }
    private fun reset(title:String) { stopPlayback(); root.removeAllViews(); root.addView(label(title)) }
    private fun playlists():MutableList<Playlist> {
        val arr=JSONArray(prefs.getString("playlists","[]"))
        return MutableList(arr.length()){ val o=arr.getJSONObject(it); Playlist(o.getString("name"),o.getString("url"),o.optBoolean("enabled",true)) }
    }
    private fun savePlaylists(list:List<Playlist>) {
        val arr=JSONArray(); list.forEach { arr.put(JSONObject().put("name",it.name).put("url",it.url).put("enabled",it.enabled)) }
        prefs.edit().putString("playlists",arr.toString()).apply()
    }
    private fun showHome() {
        reset("HomeTV")
        root.addView(button("Channels"){showChannels()})
        root.addView(button("Refresh playlists"){refresh{showChannels()}})
        root.addView(button("Manage playlists"){managePlaylists()})
        root.addView(button("Settings"){settings()})
        root.addView(button("Resume last channel"){resumeLast()})
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
                savePlaylists(list);managePlaylists()
            } else Toast.makeText(this,"Enter name and HTTP(S) URL",Toast.LENGTH_LONG).show()
        }.setNegativeButton("Cancel",null).show()
    }
    private fun refresh(done:()->Unit){
        val sources=playlists().filter{it.enabled}
        if(sources.isEmpty()){channels.clear();done();return}
        Toast.makeText(this,"Loading playlists…",Toast.LENGTH_SHORT).show()
        thread {
            val found=mutableListOf<Channel>()
            sources.forEach { p ->
                try {
                    val conn=URL(p.url).openConnection().apply { connectTimeout=10000;readTimeout=15000 }
                    val body=conn.getInputStream().bufferedReader().use{it.readText()}
                    found.addAll(parseM3u(body,p.name))
                } catch(_:Exception){}
            }
            runOnUiThread {
                channels=found.distinctBy{it.name.lowercase()+"|"+it.url}.toMutableList()
                Toast.makeText(this,"${channels.size} channels loaded",Toast.LENGTH_SHORT).show()
                done()
            }
        }
    }
    private fun parseM3u(body:String,source:String):List<Channel>{
        val out=mutableListOf<Channel>();var name="";var group="Other";var logo=""
        fun attr(line:String,key:String)=Regex(Regex.escape(key)+"=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?:""
        body.lineSequence().forEach { raw ->
            val line=raw.trim()
            if(line.startsWith("#EXTINF",true)){
                name=line.substringAfterLast(",","").trim()
                group=attr(line,"group-title").ifBlank{"Other"}
                logo=attr(line,"tvg-logo")
            } else if(line.isNotEmpty() && !line.startsWith("#") && name.isNotEmpty()){
                out.add(Channel(name,line,group,logo,source))
                // Multiple stream URLs following one EXTINF become separate playable entries.
            }
        }
        return out
    }
    private fun showChannels(){
        reset("Channels (${channels.size})")
        root.addView(button("Refresh"){refresh{showChannels()}})
        val groups=channels.map{it.group}.distinct().sorted()
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
            list.addView(button(c.name){playIndex(i)})
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
    private fun playIndex(index:Int){
        if(index !in channels.indices)return
        val c=channels[index]
        if(unlockedChannel!=id(c) && (lockIds.contains(id(c)) || lockGroups.contains(c.group))){
            requirePin {unlockedChannel=id(c);playIndex(index)}
            return
        }
        stopPlayback();root.removeAllViews()
        active=index
        if(unlockedChannel!=id(c)) unlockedChannel=null
        prefs.edit().putString("last_url",c.url).apply()
        val videoId=Regex("(?:youtube\\.com/(?:live/|watch\\?v=|embed/)|youtu\\.be/)([A-Za-z0-9_-]{11})").find(c.url)?.groupValues?.get(1)
        if(videoId!=null){
            web=WebView(this).apply {
                settings.javaScriptEnabled=true
                settings.domStorageEnabled=true
                webChromeClient=WebChromeClient()
                loadDataWithBaseURL("https://www.youtube.com", "<html><body style='margin:0;background:black'><iframe width='100%' height='100%' src='https://www.youtube.com/embed/$videoId?autoplay=1' frameborder='0' allow='autoplay; encrypted-media; fullscreen' allowfullscreen></iframe></body></html>","text/html","UTF-8",null)
            }
            root.addView(web,LinearLayout.LayoutParams(-1,0,1f))
        }else{
            player=ExoPlayer.Builder(this).build()
            val view=PlayerView(this).apply{player=this@MainActivity.player;useController=false}
            root.addView(view,LinearLayout.LayoutParams(-1,0,1f))
            try{player?.setMediaItem(MediaItem.fromUri(c.url));player?.prepare();player?.play()}catch(_:Exception){Toast.makeText(this,"Stream failed",Toast.LENGTH_LONG).show()}
        }
        root.addView(button("◀ Previous"){switchChannel(-1)})
        root.addView(button("Next ▶"){switchChannel(1)})
        root.addView(button("Channel list"){showChannels()})
    }
    private fun switchChannel(delta:Int){if(channels.isNotEmpty())playIndex((active+delta+channels.size)%channels.size)}
    private fun resumeLast(){
        val url=prefs.getString("last_url",null)?:return
        val index=channels.indexOfFirst{it.url==url}
        if(index>=0)playIndex(index)
    }
    private fun settings(){
        reset("HomeTV Settings")
        root.addView(button("Manage playlists"){managePlaylists()})
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
        root.addView(button("Try launch at device boot: "+if(prefs.getBoolean("boot",false))"ON" else "OFF"){
            prefs.edit().putBoolean("boot",!prefs.getBoolean("boot",false)).apply();settings()
        })
        root.addView(label("Boot launching is device-dependent. The TV power key cannot be overridden by an ordinary app."))
        root.addView(label("Remote: Left/Right = previous/next while playing; Back = channel list."))
        root.addView(button("Home"){showHome()})
    }
    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean{
        if(active>=0 && (keyCode==KeyEvent.KEYCODE_DPAD_LEFT || keyCode==KeyEvent.KEYCODE_CHANNEL_DOWN)){switchChannel(-1);return true}
        if(active>=0 && (keyCode==KeyEvent.KEYCODE_DPAD_RIGHT || keyCode==KeyEvent.KEYCODE_CHANNEL_UP)){switchChannel(1);return true}
        return super.onKeyDown(keyCode,event)
    }
    override fun onBackPressed(){active=-1;unlockedChannel=null;showChannels()}
    private fun stopPlayback(){player?.release();player=null;web?.destroy();web=null;active=-1}
    override fun onDestroy(){stopPlayback();super.onDestroy()}
}
