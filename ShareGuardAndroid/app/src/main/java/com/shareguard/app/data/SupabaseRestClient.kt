package com.shareguard.app.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

data class RemoteProfile(
    val id: String, val displayName: String, val role: String, val verified: Boolean,
    val trustScore: Int, val rating: Double, val dealCount: Int
)
data class RemoteReview(val rating: Int, val content: String, val createdAt: String)

data class AuthResult(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val userId: String? = null,
    val expiresAt: Long = 0L,
    val message: String
) {
    val isSignedIn: Boolean get() = !accessToken.isNullOrBlank()
}

data class RemoteItem(
    val id: String,
    val ownerId: String,
    val title: String,
    val category: String,
    val description: String,
    val dailyPrice: Int,
    val deposit: Int,
    val address: String,
    val latitude: Double?,
    val longitude: Double?,
    val imagePaths: List<String> = emptyList(),
    val status: String = "active"
)

data class NewRemoteItem(
    val title: String,
    val category: String,
    val description: String,
    val dailyPrice: Int,
    val deposit: Int,
    val address: String,
    val latitude: Double?,
    val longitude: Double?
)

data class RemoteReservation(
    val id: String,
    val itemId: String,
    val borrowerId: String,
    val startDate: String,
    val endDate: String,
    val rentalFee: Int,
    val deposit: Int,
    val totalAmount: Int,
    val status: String
)

data class RemoteContract(
    val id: String,
    val reservationId: String,
    val status: String,
    val ownerSigned: Boolean,
    val borrowerSigned: Boolean,
    val beforePhotos: Int = 0,
    val afterPhotos: Int = 0
)

data class RemoteChatMessage(
    val id: Long,
    val roomId: String,
    val senderId: String,
    val body: String,
    val createdAt: String
)

data class ChatMedia(val kind: String, val path: String) {
    companion object {
        private val encoded = Regex("^\\[\\[shareguard-media:(image|video):([A-Za-z0-9_-]+/[A-Za-z0-9_-]+/[A-Za-z0-9_-]+\\.(?:jpg|png|webp|mp4|webm|mov))]]$")
        fun fromBody(body: String): ChatMedia? = encoded.matchEntire(body)?.let {
            ChatMedia(it.groupValues[1], it.groupValues[2])
        }
    }

    fun asBody(): String = "[[shareguard-media:$kind:$path]]"
}

data class RemoteReport(
    val id: String,
    val reservationId: String,
    val type: String,
    val reason: String,
    val status: String
)

data class RemoteChatRoom(val id: String, val reservationId: String, val hidden: Boolean, val lastBody: String, val unreadCount: Int)
data class RemoteDateRequest(val id: String, val requestedBy: String, val startDate: String, val endDate: String, val rentalFee: Int)
data class RemoteNotification(val id: Long, val kind: String, val title: String, val body: String, val reservationId: String?, val createdAt: String, val unread: Boolean)

class SupabaseRestClient(
    private val baseUrl: String,
    private val publishableKey: String
) {
    private val itemSelect = "id,owner_id,title,category,description,daily_price,deposit,address,latitude,longitude,status,item_images(storage_path,sort_order)"

    fun itemImageUrl(path: String): String = baseUrl.trimEnd('/') +
        "/storage/v1/object/public/item-images/" + path.split('/').joinToString("/") { Uri.encode(it) }

    fun updateDisplayName(name: String, token: String) {
        require(name.trim().length in 2..30) { "닉네임은 2~30자로 입력하세요." }
        rpc("update_my_display_name", JSONObject().put("p_display_name", name.trim()), token)
    }
    val isConfigured: Boolean =
        baseUrl.startsWith("https://") && publishableKey.startsWith("sb_publishable_")

    fun signIn(email: String, password: String): AuthResult {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
        return parseAuth(request("/auth/v1/token?grant_type=password", "POST", body))
    }

    fun signUp(email: String, password: String, displayName: String): AuthResult {
        val body = JSONObject()
            .put("email", email.trim())
            .put("password", password)
            .put("data", JSONObject().put("display_name", displayName.trim()))
        return parseAuth(request("/auth/v1/signup", "POST", body))
    }

    fun googleSignInUrl(redirectUri: String, codeChallenge: String): String =
        Uri.parse(baseUrl.trimEnd('/') + "/auth/v1/authorize")
            .buildUpon()
            .appendQueryParameter("provider", "google")
            .appendQueryParameter("redirect_to", redirectUri)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", "s256")
            .build()
            .toString()

    fun exchangeCodeForSession(code: String, codeVerifier: String): AuthResult {
        val body = JSONObject()
            .put("auth_code", code)
            .put("code_verifier", codeVerifier)
        return parseAuth(request("/auth/v1/token?grant_type=pkce", "POST", body))
    }

    fun refreshSession(refreshToken: String): AuthResult {
        val body = JSONObject().put("refresh_token", refreshToken)
        return parseAuth(request("/auth/v1/token?grant_type=refresh_token", "POST", body))
    }

    fun signOut(accessToken: String) {
        request("/auth/v1/logout", "POST", JSONObject(), accessToken)
    }

    fun fetchCurrentProfile(accessToken: String): RemoteProfile {
        val auth = request("/auth/v1/user", "GET", accessToken = accessToken)
        check(auth.isSuccessful) { auth.errorMessage() }
        val userId = JSONObject(auth.body).getString("id")
        return fetchProfiles(listOf(userId), accessToken).singleOrNull()
            ?: error("사용자 프로필을 찾을 수 없습니다. DB 마이그레이션을 확인해주세요.")
    }

    fun fetchProfiles(ids: List<String>, accessToken: String? = null): List<RemoteProfile> {
        if (ids.isEmpty()) return emptyList()
        ids.forEach { UUID.fromString(it) }
        return ids.distinct().chunked(50).flatMap { group ->
            val response = request("/rest/v1/profiles?select=id,display_name,role,verified,trust_score,rating,deal_count&id=in.(${group.joinToString(",")})",
                "GET", accessToken = accessToken)
            check(response.isSuccessful) { response.errorMessage() }
            val rows = JSONArray(response.body)
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                RemoteProfile(row.getString("id"), row.getString("display_name"), row.getString("role"),
                    row.getBoolean("verified"), row.getInt("trust_score"), row.getDouble("rating"), row.getInt("deal_count"))
            }
        }
    }

    fun fetchProfileReviews(userId: String, accessToken: String): List<RemoteReview> {
        UUID.fromString(userId)
        val response = request("/rest/v1/reviews?select=rating,content,created_at&reviewee_id=eq.$userId&order=created_at.desc&limit=10",
            "GET", accessToken = accessToken)
        check(response.isSuccessful) { response.errorMessage() }
        val rows = JSONArray(response.body)
        return (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            RemoteReview(it.getInt("rating"), it.optString("content"), it.getString("created_at"))
        } }
    }

    fun fetchItems(accessToken: String? = null, itemIds: List<String>? = null): List<RemoteItem> {
        if (itemIds != null) {
            if (itemIds.isEmpty()) return emptyList()
            itemIds.forEach { UUID.fromString(it) }
            if (itemIds.size > 50) return itemIds.distinct().chunked(50).flatMap { fetchItems(accessToken, it) }
        }
        val filter = if (itemIds == null) "&status=eq.active" else "&id=in.(${itemIds.distinct().joinToString(",")})"
        val response = request(
            "/rest/v1/items?select=$itemSelect" +
                "$filter&order=created_at.desc",
            "GET",
            accessToken = accessToken
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())

        val rows = JSONArray(response.body)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            RemoteItem(
                id = row.getString("id"),
                ownerId = row.getString("owner_id"),
                title = row.getString("title"),
                category = row.optString("category", "기타"),
                description = row.optString("description", ""),
                dailyPrice = row.getInt("daily_price"),
                deposit = row.getInt("deposit"),
                address = row.optString("address", "거래 위치 협의"),
                latitude = row.optNullableDouble("latitude"),
                longitude = row.optNullableDouble("longitude"),
                imagePaths = row.imagePaths(),
                status = row.optString("status", "active")
            )
        }
    }

    fun fetchItemsByIds(ids: Collection<String>, token: String): List<RemoteItem> = ids.distinct().chunked(40).flatMap { batch ->
        val response = request("/rest/v1/items?select=$itemSelect&id=in.(${batch.joinToString(",") { Uri.encode(it) }})", "GET", accessToken = token)
        check(response.isSuccessful) { response.errorMessage() }
        val rows = JSONArray(response.body)
        (0 until rows.length()).map { index -> rows.getJSONObject(index).let { row ->
            RemoteItem(row.getString("id"), row.getString("owner_id"), row.getString("title"), row.getString("category"), row.optString("description"), row.getInt("daily_price"), row.getInt("deposit"), row.getString("address"), row.optNullableDouble("latitude"), row.optNullableDouble("longitude"), row.imagePaths(), row.optString("status", "active"))
        } }
    }

    private fun JSONObject.imagePaths(): List<String> {
        val images = optJSONArray("item_images") ?: return emptyList()
        return (0 until images.length()).map { images.getJSONObject(it) }.sortedBy { it.optInt("sort_order") }.map { it.getString("storage_path") }
    }

    fun removeReservation(id: String, token: String) { rpc("remove_rental_reservation", JSONObject().put("p_reservation_id", id), token) }

    fun fetchArchivedReservations(token: String): Set<String> {
        val response = request("/rest/v1/reservation_archives?select=reservation_id", "GET", accessToken = token)
        check(response.isSuccessful) { response.errorMessage() }
        val rows = JSONArray(response.body)
        return (0 until rows.length()).map { rows.getJSONObject(it).getString("reservation_id") }.toSet()
    }

    fun proposeDates(id: String, start: String, end: String, token: String) {
        rpc("propose_reservation_dates", JSONObject().put("p_reservation_id", id).put("p_start_date", start).put("p_end_date", end), token)
    }

    fun respondDates(id: String, accept: Boolean, token: String) {
        rpc("respond_reservation_dates", JSONObject().put("p_request_id", id).put("p_accept", accept), token)
    }

    fun fetchDateRequests(reservationId: String, token: String): List<RemoteDateRequest> {
        val response = request("/rest/v1/reservation_date_requests?select=*&reservation_id=eq.${Uri.encode(reservationId)}&status=eq.pending", "GET", accessToken = token)
        check(response.isSuccessful) { response.errorMessage() }
        val rows = JSONArray(response.body)
        return (0 until rows.length()).map { rows.getJSONObject(it).let { r ->
            RemoteDateRequest(r.getString("id"), r.getString("requested_by"), r.getString("start_date"), r.getString("end_date"), r.getInt("rental_fee"))
        } }
    }

    fun fetchChatRooms(token: String): List<RemoteChatRoom> {
        val rows = JSONArray(rpc("list_my_chat_rooms", JSONObject(), token).body)
        return (0 until rows.length()).map { rows.getJSONObject(it).let { r ->
            RemoteChatRoom(r.getString("id"), r.getString("reservation_id"), !r.isNull("hidden_at"), r.optString("last_body", "").takeUnless { it == "null" }.orEmpty(), r.optInt("unread_count"))
        } }
    }

    fun hideChatRoom(id: String, token: String) { rpc("hide_chat_room", JSONObject().put("p_room_id", id), token) }

    fun fetchNotifications(token: String, afterId: Long? = null): List<RemoteNotification> {
        val query = if (afterId == null) "order=id.desc&limit=100" else "id=gt.$afterId&order=id.asc&limit=100"
        val response = request("/rest/v1/app_notifications?select=*&$query", "GET", accessToken = token)
        check(response.isSuccessful) { response.errorMessage() }
        val rows = JSONArray(response.body)
        return (0 until rows.length()).map { rows.getJSONObject(it).let { r ->
            RemoteNotification(r.getLong("id"), r.getString("kind"), r.getString("title"), r.getString("body"), r.optString("reservation_id").takeUnless { it == "null" }, r.getString("created_at"), r.isNull("read_at"))
        } }
    }

    fun markNotificationsRead(ids: List<Long>, token: String) {
        if (ids.isEmpty()) return
        val response = request("/rest/v1/app_notifications?id=in.(${ids.joinToString(",")})", "PATCH", JSONObject().put("read_at", java.time.Instant.now().toString()), token)
        check(response.isSuccessful) { response.errorMessage() }
    }

    fun insertItem(item: NewRemoteItem, accessToken: String): RemoteItem {
        val payload = JSONObject()
            .put("status", "hidden")
            .put("title", item.title)
            .put("category", item.category)
            .put("description", item.description)
            .put("daily_price", item.dailyPrice)
            .put("deposit", item.deposit)
            .put("address", item.address)
            .putNullable("latitude", item.latitude)
            .putNullable("longitude", item.longitude)
        val response = request(
            "/rest/v1/items",
            "POST",
            payload,
            accessToken,
            mapOf("Prefer" to "return=representation")
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
        val row = JSONArray(response.body).getJSONObject(0)
        return RemoteItem(
            id = row.getString("id"),
            ownerId = row.getString("owner_id"),
            title = row.getString("title"),
            category = row.optString("category", "기타"),
            description = row.optString("description", ""),
            dailyPrice = row.getInt("daily_price"),
            deposit = row.getInt("deposit"),
            address = row.optString("address", "거래 위치 협의"),
            latitude = row.optNullableDouble("latitude"),
            longitude = row.optNullableDouble("longitude")
        )
    }

    fun updateItem(itemId: String, item: NewRemoteItem, accessToken: String): RemoteItem {
        val payload = JSONObject()
            .put("title", item.title)
            .put("category", item.category)
            .put("description", item.description)
            .put("daily_price", item.dailyPrice)
            .put("deposit", item.deposit)
            .put("address", item.address)
            .putNullable("latitude", item.latitude)
            .putNullable("longitude", item.longitude)
        val response = request(
            "/rest/v1/items?id=eq.$itemId",
            "PATCH",
            payload,
            accessToken,
            mapOf("Prefer" to "return=representation")
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
        val row = JSONArray(response.body).getJSONObject(0)
        return RemoteItem(
            id = row.getString("id"),
            ownerId = row.getString("owner_id"),
            title = row.getString("title"),
            category = row.optString("category", "기타"),
            description = row.optString("description", ""),
            dailyPrice = row.getInt("daily_price"),
            deposit = row.getInt("deposit"),
            address = row.optString("address", "거래 위치 협의"),
            latitude = row.optNullableDouble("latitude"),
            longitude = row.optNullableDouble("longitude")
        )
    }

    fun hideItem(itemId: String, accessToken: String) {
        val response = request(
            "/rest/v1/items?id=eq.$itemId",
            "PATCH",
            JSONObject().put("status", "hidden"),
            accessToken,
            mapOf("Prefer" to "return=representation")
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
        if (JSONArray(response.body).length() == 0) throw IllegalStateException("게시물을 찾지 못했거나 삭제 권한이 없습니다.")
    }

    fun publishItem(itemId: String, token: String) {
        val response = request("/rest/v1/items?id=eq.${Uri.encode(itemId)}", "PATCH", JSONObject().put("status", "active"), token)
        check(response.isSuccessful) { response.errorMessage() }
    }

    fun uploadItemImage(
        itemId: String,
        userId: String,
        sortOrder: Int,
        bytes: ByteArray,
        mimeType: String,
        accessToken: String
    ): String {
        require(sortOrder in 0..4) { "물품 사진은 최대 5장입니다." }
        require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) { "사진은 10MB 이하여야 합니다." }
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val storagePath = "$userId/$itemId/${sortOrder}_${System.currentTimeMillis()}.$extension"
        val upload = binaryRequest(
            "/storage/v1/object/item-images/$storagePath",
            "POST",
            bytes,
            mimeType,
            accessToken
        )
        if (!upload.isSuccessful) throw IllegalStateException(upload.errorMessage())
        val metadata = request(
            "/rest/v1/item_images",
            "POST",
            JSONObject()
                .put("item_id", itemId)
                .put("storage_path", storagePath)
                .put("sort_order", sortOrder),
            accessToken,
            mapOf("Prefer" to "return=minimal")
        )
        if (!metadata.isSuccessful) throw IllegalStateException(metadata.errorMessage())
        return storagePath
    }

    fun fetchReservations(accessToken: String): List<RemoteReservation> {
        val response = request(
            "/rest/v1/reservations?select=id,item_id,borrower_id,start_date,end_date,rental_fee,deposit,total_amount,status" +
                "&order=created_at.desc",
            "GET",
            accessToken = accessToken
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
        val rows = JSONArray(response.body)
        return (0 until rows.length()).map { index -> rows.getJSONObject(index).toRemoteReservation() }
    }

    fun fetchContracts(accessToken: String): List<RemoteContract> {
        val response = request(
            "/rest/v1/rental_contracts?select=id,reservation_id,status,owner_signed_at,borrower_signed_at&order=created_at.desc",
            "GET",
            accessToken = accessToken
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
        val photoResponse = request(
            "/rest/v1/condition_photos?select=contract_id,phase",
            "GET",
            accessToken = accessToken
        )
        if (!photoResponse.isSuccessful) throw IllegalStateException(photoResponse.errorMessage())
        val photos = JSONArray(photoResponse.body)
        val counts = mutableMapOf<String, Pair<Int, Int>>()
        (0 until photos.length()).forEach { index ->
            val photo = photos.getJSONObject(index)
            val contractId = photo.getString("contract_id")
            val current = counts[contractId] ?: (0 to 0)
            counts[contractId] = if (photo.getString("phase") == "before") {
                current.copy(first = current.first + 1)
            } else {
                current.copy(second = current.second + 1)
            }
        }
        val rows = JSONArray(response.body)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val photoCount = counts[row.getString("id")] ?: (0 to 0)
            RemoteContract(
                id = row.getString("id"),
                reservationId = row.getString("reservation_id"),
                status = row.getString("status"),
                ownerSigned = !row.isNull("owner_signed_at"),
                borrowerSigned = !row.isNull("borrower_signed_at"),
                beforePhotos = photoCount.first,
                afterPhotos = photoCount.second
            )
        }
    }

    fun createReservation(
        itemId: String,
        startDate: String,
        endDate: String,
        accessToken: String
    ): RemoteReservation {
        val response = rpc("create_rental_reservation", JSONObject().put("p_item_id", itemId)
            .put("p_start_date", startDate).put("p_end_date", endDate), accessToken)
        return JSONObject(response.body).toRemoteReservation()
    }

    fun approveReservation(reservationId: String, accessToken: String): RemoteContract {
        val response = request(
            "/rest/v1/rpc/approve_reservation",
            "POST",
            JSONObject().put("p_reservation_id", reservationId),
            accessToken
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
        val row = JSONObject(response.body)
        return row.toRemoteContract()
    }

    fun rejectReservation(reservationId: String, accessToken: String) {
        val response = request(
            "/rest/v1/rpc/reject_reservation",
            "POST",
            JSONObject().put("p_reservation_id", reservationId),
            accessToken
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
    }

    fun signContract(contractId: String, accessToken: String): RemoteContract {
        val response = rpc("sign_rental_contract", JSONObject().put("p_contract_id", contractId), accessToken)
        return JSONObject(response.body).toRemoteContract()
    }

    fun uploadConditionPhoto(
        contractId: String,
        phase: String,
        userId: String,
        bytes: ByteArray,
        mimeType: String,
        accessToken: String
    ): String {
        require(phase == "before" || phase == "after") { "사진 단계가 올바르지 않습니다." }
        require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024) { "사진은 10MB 이하여야 합니다." }
        val extension = when (mimeType) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val storagePath = "$userId/$contractId/$phase/${System.currentTimeMillis()}.$extension"
        val upload = binaryRequest(
            "/storage/v1/object/condition-photos/$storagePath",
            "POST",
            bytes,
            mimeType,
            accessToken
        )
        if (!upload.isSuccessful) throw IllegalStateException(upload.errorMessage())

        val metadata = request(
            "/rest/v1/condition_photos",
            "POST",
            JSONObject()
                .put("contract_id", contractId)
                .put("phase", phase)
                .put("storage_path", storagePath),
            accessToken,
            mapOf("Prefer" to "return=minimal")
        )
        if (!metadata.isSuccessful) throw IllegalStateException(metadata.errorMessage())
        return storagePath
    }

    fun fetchChatRoom(reservationId: String, accessToken: String): String? {
        val response = request(
            "/rest/v1/chat_rooms?select=id&reservation_id=eq.$reservationId&limit=1",
            "GET",
            accessToken = accessToken
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
        val rows = JSONArray(response.body)
        return if (rows.length() == 0) null else rows.getJSONObject(0).getString("id")
    }

    fun fetchChatMessages(roomId: String, accessToken: String): List<RemoteChatMessage> {
        val response = request(
            "/rest/v1/chat_messages?select=id,room_id,sender_id,body,created_at&room_id=eq.$roomId&order=created_at.asc",
            "GET",
            accessToken = accessToken
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
        val rows = JSONArray(response.body)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            RemoteChatMessage(
                row.getLong("id"),
                row.getString("room_id"),
                row.getString("sender_id"),
                row.getString("body"),
                row.optString("created_at", "")
            )
        }
    }

    fun markChatRead(roomId: String, userId: String, accessToken: String) {
        val response = request(
            "/rest/v1/chat_room_members?room_id=eq.$roomId&user_id=eq.$userId",
            "PATCH",
            JSONObject().put("last_read_at", java.time.Instant.now().toString()),
            accessToken
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
    }

    fun sendChatMessage(roomId: String, body: String, accessToken: String) {
        require(body.trim().isNotEmpty() && body.length <= 2000) { "메시지는 1~2000자로 입력하세요." }
        val response = request(
            "/rest/v1/chat_messages",
            "POST",
            JSONObject().put("room_id", roomId).put("body", body.trim()),
            accessToken,
            mapOf("Prefer" to "return=minimal")
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
    }

    fun sendChatMedia(roomId: String, userId: String, bytes: ByteArray, mimeType: String, accessToken: String): ChatMedia {
        val format = when (mimeType) {
            "image/jpeg" -> "image" to "jpg"
            "image/png" -> "image" to "png"
            "image/webp" -> "image" to "webp"
            "video/mp4" -> "video" to "mp4"
            "video/webm" -> "video" to "webm"
            "video/quicktime" -> "video" to "mov"
            else -> throw IllegalArgumentException("JPG·PNG·WEBP 사진 또는 MP4·WEBM·MOV 영상만 보낼 수 있습니다.")
        }
        require(bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024) { "첨부파일은 20MB 이하여야 합니다." }
        val media = ChatMedia(format.first, "$roomId/$userId/${UUID.randomUUID()}.${format.second}")
        val upload = binaryRequest("/storage/v1/object/chat-media/${media.path}", "POST", bytes, mimeType, accessToken)
        if (!upload.isSuccessful) throw IllegalStateException(upload.errorMessage())
        try {
            sendChatMessage(roomId, media.asBody(), accessToken)
        } catch (error: Exception) {
            // Avoid leaving an orphaned private file when the chat insert fails.
            runCatching { request("/storage/v1/object/chat-media/${media.path}", "DELETE", accessToken = accessToken) }
            throw error
        }
        return media
    }

    fun downloadChatMedia(media: ChatMedia, accessToken: String): ByteArray {
        check(isConfigured) { "Supabase 연결이 설정되지 않았습니다." }
        val connection = URL(baseUrl.trimEnd('/') + "/storage/v1/object/authenticated/chat-media/${media.path}").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("apikey", publishableKey)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("첨부파일을 열 수 없습니다. ${detail.take(120)}")
            }
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 20 * 1024 * 1024) { "첨부파일이 너무 큽니다." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    fun createReview(
        reservationId: String,
        revieweeId: String,
        rating: Int,
        content: String,
        accessToken: String
    ) {
        val response = request(
            "/rest/v1/reviews",
            "POST",
            JSONObject()
                .put("reservation_id", reservationId)
                .put("reviewee_id", revieweeId)
                .put("rating", rating)
                .put("content", content),
            accessToken,
            mapOf("Prefer" to "return=minimal")
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
    }

    fun openDispute(
        reservationId: String,
        reportedUserId: String,
        reason: String,
        accessToken: String
    ) {
        rpc(
            "open_rental_dispute",
            JSONObject()
                .put("p_reservation_id", reservationId)
                .put("p_reported_user_id", reportedUserId)
                .put("p_report_type", "damage")
                .put("p_reason", reason),
            accessToken
        )
    }

    fun fetchReports(accessToken: String): List<RemoteReport> {
        val response = request(
            "/rest/v1/reports?select=id,reservation_id,report_type,reason,status&order=created_at.desc",
            "GET",
            accessToken = accessToken
        )
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
        val rows = JSONArray(response.body)
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            RemoteReport(
                id = row.getString("id"),
                reservationId = row.getString("reservation_id"),
                type = row.optString("report_type", "other"),
                reason = row.optString("reason", ""),
                status = row.optString("status", "open")
            )
        }
    }

    private fun rpc(name: String, body: JSONObject, accessToken: String): HttpResponse {
        val response = request("/rest/v1/rpc/$name", "POST", body, accessToken)
        if (!response.isSuccessful) throw IllegalStateException(response.errorMessage())
        return response
    }

    private fun JSONObject.toRemoteContract() = RemoteContract(
        id = getString("id"),
        reservationId = getString("reservation_id"),
        status = getString("status"),
        ownerSigned = !isNull("owner_signed_at"),
        borrowerSigned = !isNull("borrower_signed_at")
    )

    private fun JSONObject.toRemoteReservation() = RemoteReservation(
        id = getString("id"),
        itemId = getString("item_id"),
        borrowerId = getString("borrower_id"),
        startDate = getString("start_date"),
        endDate = getString("end_date"),
        rentalFee = getInt("rental_fee"),
        deposit = getInt("deposit"),
        totalAmount = getInt("total_amount"),
        status = getString("status")
    )

    private fun parseAuth(response: HttpResponse): AuthResult {
        if (!response.isSuccessful) return AuthResult(message = response.errorMessage())
        val json = JSONObject(response.body)
        val token = json.optString("access_token").ifBlank { null }
        val refreshToken = json.optString("refresh_token").ifBlank { null }
        val userId = json.optJSONObject("user")?.optString("id")?.ifBlank { null }
        val expiresIn = json.optLong("expires_in", 3600L)
        val expiresAt = if (token == null) 0L else System.currentTimeMillis() + expiresIn * 1000L
        val message = if (token == null) {
            "가입 확인 메일을 확인한 뒤 로그인하세요."
        } else {
            "Supabase 로그인 완료"
        }
        return AuthResult(token, refreshToken, userId, expiresAt, message)
    }

    private fun request(
        path: String,
        method: String,
        body: JSONObject? = null,
        accessToken: String? = null,
        extraHeaders: Map<String, String> = emptyMap()
    ): HttpResponse {
        check(isConfigured) { "Supabase URL과 공개 키가 설정되지 않았습니다." }
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("apikey", publishableKey)
            connection.setRequestProperty("Accept", "application/json")
            if (!accessToken.isNullOrBlank()) {
                connection.setRequestProperty("Authorization", "Bearer $accessToken")
            }
            extraHeaders.forEach(connection::setRequestProperty)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun binaryRequest(
        path: String,
        method: String,
        bytes: ByteArray,
        contentType: String,
        accessToken: String
    ): HttpResponse {
        check(isConfigured) { "Supabase URL과 공개 키가 설정되지 않았습니다." }
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("apikey", publishableKey)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Content-Type", contentType)
            connection.setRequestProperty("x-upsert", "false")
            connection.outputStream.use { it.write(bytes) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            HttpResponse(status, responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.optNullableDouble(name: String): Double? =
        if (isNull(name)) null else optDouble(name).takeUnless(Double::isNaN)

    private fun JSONObject.putNullable(name: String, value: Double?): JSONObject =
        if (value == null) put(name, JSONObject.NULL) else put(name, value)

    private data class HttpResponse(val status: Int, val body: String) {
        val isSuccessful: Boolean get() = status in 200..299

        fun errorMessage(): String {
            if (body.isBlank()) {
                return "Supabase 요청 실패 (HTTP $status)"
            }
            return runCatching {
                val json = JSONObject(body)
                listOf("msg", "message", "error_description")
                    .map { json.optString(it) }.firstOrNull { it.isNotBlank() } ?: body
            }.getOrDefault(body)
        }
    }
}
