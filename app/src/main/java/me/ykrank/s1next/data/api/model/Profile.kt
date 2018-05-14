package me.ykrank.s1next.data.api.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.github.ykrank.androidtools.util.L
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.*

/**
 * Created by ykrank on 2017/1/8.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class Profile : Account {

    var homeUsername: String? = null
    var homeUid: String? = null
    var groupTitle: String? = null
    var friends: Int = 0
    var threads: Int = 0
    var replies: Int = 0
    var signHtml: String? = null
    var onlineHour: Int = 0
    var regDate: Long? = null
    var lastVisitDate: Long? = null
    var lastActiveDate: Long? = null
    var lastPostDate: Long? = null
    var credits: Int = 0
    var combatEffectiveness: Int = 0
    var gold: Int = 0
    var rp: Int = 0
    var shameSense: Int = 0

    constructor()

    @JsonCreator
    constructor(@JsonProperty("extcredits") extCredits: JsonNode, @JsonProperty("space") space: JsonNode) {
        this.homeUsername = space.get("username")?.asText()
        this.homeUid = space.get("uid")?.asText()
        this.groupTitle = space.get("group")?.get("grouptitle")?.asText()
        this.friends = space.get("friends")?.asInt() ?: -1
        val posts = space.get("posts")?.asInt() ?: -1
        this.threads = space.get("threads")?.asInt() ?: -1
        this.replies = posts - threads
        this.signHtml = space.get("sightml")?.asText()
        this.onlineHour = space.get("oltime")?.asInt() ?: -1
        this.regDate = space.get("regdate")?.asLong()
        this.lastVisitDate = space.get("lastvisit")?.asLong()
        this.lastActiveDate = space.get("lastactivity")?.asLong()
        this.lastPostDate = space.get("lastpost")?.asLong()
        this.credits = space.get("credits")?.asInt() ?: -1
        this.combatEffectiveness = space.get("extcredits1")?.asInt() ?: -1
        this.gold = space.get("extcredits2")?.asInt() ?: -1
        this.rp = space.get("extcredits4")?.asInt() ?: -1
        this.shameSense = space.get("extcredits7")?.asInt() ?: -1
    }

    companion object {
        //2018-4-14 22:20
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

        fun fromHtml(html: String): Profile {
            val profile = Profile()
            try {
                val document = Jsoup.parse(html)
                val profileDiv = document.select("div.u_profile")[0]
                //<div class="pbm mbm bbda cl">
                val baseElement = profileDiv.child(0)
                //<h2 class="mbn">
                val nameEle = baseElement.child(0)
                profile.homeUsername = nameEle.child(0).text()
                profile.homeUid = nameEle.selectFirst("span").text().trim().let {
                    it.substring(6, it.length - 1)
                }
                profile.signHtml = nameEle.selectFirst("table")?.html()
                val countEle = baseElement.child(3).child(0)
                profile.friends = countEle.child(1).text().trim().substring(4).toInt()
                profile.replies = countEle.child(3).text().trim().substring(4).toInt()
                profile.threads = countEle.child(5).text().trim().substring(4).toInt()

                //活跃概况
                val timeElement = profileDiv.child(1)
                profile.groupTitle = timeElement.child(1).selectFirst("span").text()
                //<ul id="pbbs" class="pf_l">
                val pbbsEle = profileDiv.getElementById("pbbs")
                pbbsEle.children().forEach {
                    when (it.child(0).text().trim()) {
                        "在线时间" -> profile.onlineHour = it.textNodes()[0].text().trim().let {
                            it.substring(0, it.length - 3)
                        }.toInt()
                    //TODO 解析界面
                    }
                }
                profile.onlineHour = pbbsEle.child(0).textNodes()[0].text().trim().let {
                    it.substring(0, it.length - 3)
                }.toInt()
                profile.regDate = pbbsEle.child(1).textNodes()[0].text().trim().let {
                    df.parse(it).time / 1000
                }
                profile.lastVisitDate = pbbsEle.child(2).textNodes()[0].text().trim().let {
                    df.parse(it).time / 1000
                }
                profile.lastVisitDate = pbbsEle.child(2).textNodes()[0].text().trim().let {
                    df.parse(it).time / 1000
                }

                //统计信息
                val postElement = profileDiv.getElementById("psts")
            } catch (e: Exception) {
                L.report(e)
            }

            return profile
        }
    }
}
