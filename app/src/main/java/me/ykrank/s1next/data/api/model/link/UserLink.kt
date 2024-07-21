package me.ykrank.s1next.data.api.model.link

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class UserLink(val uid: String) : Parcelable {
    companion object {
        /**
         * Parses user link in order to get the meta info for this user.
         *
         * @param url The user space link.
         * @return The `Optional.of(userLink)` if we parse this user
         * link/ID successfully, otherwise the `Optional.absent()`.
         */
        fun parse(url: String?): UserLink? {
            if (url.isNullOrEmpty()) return null
            // example: space-uid-223963.html
            val uid = "space-uid-(\\d+)".toRegex().find(url)?.groupValues?.getOrNull(1)
            if (!uid.isNullOrEmpty()) {
                return UserLink(uid)
            }
            return null
        }
    }
}
