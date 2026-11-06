package com.softppj.hometv
internal object HomeTvPolicies {
 fun isReachableHttpResponse(statusCode:Int,contentType:String?):Boolean =
  statusCode in 200..299 && !contentType.orEmpty().contains("text/html",ignoreCase=true)
 fun shouldAutoUnhide(enabled:Boolean,isHidden:Boolean,playbackConfirmed:Boolean)=enabled && isHidden && playbackConfirmed
 fun <T> matchEpg(guide:Map<String,List<T>>,epgId:String,channelName:String):List<T>{
  val id=epgId.trim().lowercase(java.util.Locale.ROOT);val name=channelName.trim().lowercase(java.util.Locale.ROOT)
  return guide[id]?.takeIf{it.isNotEmpty()} ?:guide[name] ?:emptyList()
 }
 fun nextClockValue(values:List<String>,currentIndex:Int):Pair<String,Int>?{
  if(values.isEmpty())return null
  val index=((currentIndex%values.size)+values.size)%values.size
  return values[index] to ((index+1)%values.size)
 }
}
