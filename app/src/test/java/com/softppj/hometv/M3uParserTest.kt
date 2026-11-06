package com.softppj.hometv
import org.junit.Assert.*
import org.junit.Test
class M3uParserTest {
 @Test fun parsesAlternateUrlsAndMetadataHeaders(){
  val body="""#EXTM3U
#EXTINF:-1 tvg-id="asianet.cinema" tvg-logo="https://logo.example/a.png" group-title="Movies" media-type="movie",Asianet Cinema
#EXTVLCOPT:http-user-agent=HomeTV-Test
#EXTHTTP:{"Referer":"https://ref.example","Origin":"https://origin.example"}
https://stream.example/one.m3u8
https://stream.example/two.m3u8|User-Agent=Custom%20Agent&Referer=https%3A%2F%2Falt.example
"""
  val parsed=M3uParser.parse(body,"Test")
  assertEquals(2,parsed.size);assertEquals("Asianet Cinema",parsed[0].name)
  assertEquals("asianet.cinema",parsed[0].epgId);assertEquals("Movies",parsed[0].group)
  assertEquals("movie",parsed[0].mediaType);assertEquals("HomeTV-Test",parsed[0].headers["User-Agent"])
  assertEquals("https://ref.example",parsed[0].headers["Referer"]);assertEquals("https://origin.example",parsed[0].headers["Origin"])
  assertEquals("Custom Agent",parsed[1].headers["User-Agent"]);assertEquals("https://alt.example",parsed[1].headers["Referer"])
 }
 @Test fun excludesDeletedGroupsAndUnsupportedSchemes(){
  val body="""#EXTM3U
#EXTINF:-1 group-title="News",News One
https://news.example/live.m3u8
#EXTINF:-1 group-title="Deleted",Old Channel
https://old.example/live.m3u8
#EXTINF:-1 group-title="Other",Flash
rtmp://flash.example/stream
#EXTINF:-1 group-title="Other",Unsupported
ftp://example.com/channel
"""
  val parsed=M3uParser.parse(body,"Test",setOf("Test|Deleted"))
  assertEquals(2,parsed.size);assertEquals("News One",parsed[0].name);assertEquals("Flash",parsed[1].name)
 }
 @Test fun parsesTenThousandEntries(){
  val body=buildString{append("#EXTM3U\n");repeat(10_000){i->
   append("#EXTINF:-1 tvg-id=\"channel.$i\" group-title=\"Large\",Channel $i\n")
   append("https://stream.example/$i.m3u8\n")
  }}
  val parsed=M3uParser.parse(body,"Large")
  assertEquals(10_000,parsed.size);assertEquals("Channel 9999",parsed.last().name)
 }
}
