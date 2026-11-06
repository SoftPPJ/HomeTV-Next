package com.softppj.hometv
import org.junit.Assert.*
import org.junit.Test
class HomeTvPoliciesTest {
 @Test fun reachabilityRejectsHtmlAndErrors(){
  assertTrue(HomeTvPolicies.isReachableHttpResponse(200,"application/vnd.apple.mpegurl"))
  assertFalse(HomeTvPolicies.isReachableHttpResponse(200,"text/html"))
  assertFalse(HomeTvPolicies.isReachableHttpResponse(404,"video/mp2t"))
  assertFalse(HomeTvPolicies.isReachableHttpResponse(503,null))
 }
 @Test fun autoUnhideRequiresPlaybackConfirmation(){
  assertFalse(HomeTvPolicies.shouldAutoUnhide(false,true,true));assertFalse(HomeTvPolicies.shouldAutoUnhide(true,false,true))
  assertFalse(HomeTvPolicies.shouldAutoUnhide(true,true,false));assertTrue(HomeTvPolicies.shouldAutoUnhide(true,true,true))
 }
 @Test fun epgMatchesTvgIdBeforeName(){
  val guide=mapOf("asianet.cinema" to listOf("ID"),"asianet cinema" to listOf("Name"))
  assertEquals(listOf("ID"),HomeTvPolicies.matchEpg(guide,"ASIANET.CINEMA","Asianet Cinema"))
  assertEquals(listOf("Name"),HomeTvPolicies.matchEpg(guide,"missing","Asianet Cinema"))
 }
 @Test fun dateTimeDayRotationWraps(){
  val values=listOf("06 Nov 2026","18:30","Friday")
  val a=HomeTvPolicies.nextClockValue(values,0)!!;val b=HomeTvPolicies.nextClockValue(values,a.second)!!
  val c=HomeTvPolicies.nextClockValue(values,b.second)!!;val d=HomeTvPolicies.nextClockValue(values,c.second)!!
  assertEquals("06 Nov 2026",a.first);assertEquals("18:30",b.first);assertEquals("Friday",c.first);assertEquals("06 Nov 2026",d.first)
 }
}
