package me.ykrank.s1next.data.cache.api

import androidx.collection.LruCache
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.ykrank.androidtools.data.CacheParam
import com.github.ykrank.androidtools.data.Resource
import com.github.ykrank.androidtools.data.Source
import com.github.ykrank.androidtools.util.L
import com.github.ykrank.androidtools.widget.LoadTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combineTransform
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ykrank.s1next.BuildConfig
import me.ykrank.s1next.data.User
import me.ykrank.s1next.data.api.ApiCacheProvider
import me.ykrank.s1next.data.api.ApiUtil
import me.ykrank.s1next.data.api.S1Service
import me.ykrank.s1next.data.api.model.Post
import me.ykrank.s1next.data.api.model.Rate
import me.ykrank.s1next.data.api.model.wrapper.ForumGroupsWrapper
import me.ykrank.s1next.data.api.model.wrapper.PostsWrapper
import me.ykrank.s1next.data.api.model.wrapper.RatePostsWrapper
import me.ykrank.s1next.data.api.model.wrapper.ThreadsWrapper
import me.ykrank.s1next.data.cache.biz.CacheBiz
import me.ykrank.s1next.data.cache.biz.CacheGroupBiz
import me.ykrank.s1next.data.cache.dbmodel.Cache
import me.ykrank.s1next.data.cache.exmodel.BaseCache
import me.ykrank.s1next.data.db.biz.BlackListBiz
import me.ykrank.s1next.data.pref.DownloadPreferencesManager

class S1ApiCacheProvider(
    private val downloadPerf: DownloadPreferencesManager,
    private val s1Service: S1Service,
    private val cacheBiz: CacheBiz,
    private val cacheGroupBiz: CacheGroupBiz,
    private val user: User,
    private val jsonMapper: ObjectMapper,
    private val blackListBiz: BlackListBiz,
) : ApiCacheProvider {
    private val ratesCache = LruCache<String, BaseCache<List<Rate>>>(256)

    override suspend fun getForumGroupsWrapper(param: CacheParam?): Flow<Resource<ForumGroupsWrapper>> {
        val cacheType = ApiCacheConstants.CacheType.ForumGroups
        val apiCacheFlow = object : ApiCacheFlow<ForumGroupsWrapper>(
            downloadPerf, cacheBiz, user, jsonMapper,
            cacheType,
            param,
            ForumGroupsWrapper::class.java,
            api = {
                s1Service.getForumGroupsWrapper()
            },
            interceptor = ApiCacheValidatorCache {
                !it.data?.forumList.isNullOrEmpty()
            },
            keys = emptyList()
        ) {
            override fun getCache(): Cache? {
                // user为空时，忽略user取最新缓存，兼容完全断网时重新打开app的情况
                if (!user.isLogged) {
                    return cacheBiz.getTextZipNewest(listOf(cacheType.type))
                }
                return super.getCache()
            }
        }
        return apiCacheFlow.getFlow()
    }

    override suspend fun getThreadsWrapper(
        forumId: String,
        typeId: String?,
        page: Int,
        param: CacheParam?
    ): Flow<Resource<ThreadsWrapper>> {
        val isLogged = user.isLogged
        val cacheType = ApiCacheConstants.CacheType.Threads
        val cacheKeys = listOf(forumId, typeId, page)
        val interceptor = object : ApiCacheValidatorCache<ThreadsWrapper>({
            !it.data?.threadList.isNullOrEmpty()
        }) {
            override fun interceptSaveKey(key: String, data: ThreadsWrapper): String {
                // 用户信息未初始化时，从结果中获取uid
                if (!isLogged) {
                    val uid = data.data?.uid ?: user.uid
                    return ApiCacheFlow.getKey(uid, cacheType, cacheKeys)
                }
                return super.interceptSaveKey(key, data)
            }
        }
        val apiCacheFlow = ApiCacheFlow(
            downloadPerf, cacheBiz, user, jsonMapper,
            cacheType,
            param,
            ThreadsWrapper::class.java,
            api = {
                s1Service.getThreadsWrapper(forumId, typeId, page)
            },
            interceptor = interceptor,
            keys = cacheKeys
        )
        return apiCacheFlow.getFlow()
    }

    private fun isPostsWrapperValid(postWrapper: PostsWrapper): Boolean {
        return (postWrapper.data?.postList?.size ?: 0) > 0
    }

    @OptIn(FlowPreview::class)
    override suspend fun getPostsWrapper(
        threadId: String,
        page: Int,
        authorId: String?,
        ignoreCache: Boolean,
        onRateUpdate: ((pid: Int, rate: List<Rate>) -> Unit)?,
    ): Flow<Resource<PostsWrapper>> {
        val isLogged = user.isLogged
        val cacheType = ApiCacheConstants.CacheType.Posts
        val groupPage = page.toString()

        val cacheKeys = listOf(threadId, page)
        val cacheExtraGroups = listOf(threadId, groupPage)
        val cacheGroups = listOf(cacheType.type) + cacheExtraGroups
        val groupGroups = listOf(cacheType.type, threadId)

        // 不过滤回帖人时才走缓存
        val cacheValid = authorId.isNullOrEmpty()
        val cacheParam = CacheParam(ignoreCache = ignoreCache || !cacheValid)

        val loadTime = LoadTime()
        val ratePostFlow = getRatePostsWrapper(threadId, page, authorId, cacheParam)
        val interceptor = object : ApiCacheInterceptor<PostsWrapper> {
            override fun interceptQueryCache(cache: PostsWrapper): PostsWrapper {
                // 从缓存获取时，将帖数更新为最新
                val cacheGroup = cacheGroupBiz.query(groupGroups)
                cacheGroup?.extra?.apply {
                    if (this.isNotBlank()) {
                        cache.data?.postListInfo?.replies = this
                    }
                }
                return cache
            }

            override fun interceptSaveCache(cache: PostsWrapper): PostsWrapper? {
                // 需要后处理才能更新缓存
                return null
            }

            override fun interceptSaveKey(key: String, data: PostsWrapper): String {
                // 用户信息未初始化时，从结果中获取uid
                if (!isLogged) {
                    val uid = data.data?.uid ?: user.uid
                    return ApiCacheFlow.getKey(uid, cacheType, cacheKeys)
                }
                return super.interceptSaveKey(key, data)
            }

            override fun shouldNetDataFallback(data: PostsWrapper): Boolean {
                return !isPostsWrapperValid(data)
            }

        }

        val apiCacheFlow = ApiCacheFlow(
            downloadPerf, cacheBiz, user, jsonMapper,
            cacheType,
            cacheParam,
            PostsWrapper::class.java,
            loadTime = loadTime,
            printTime = false,
            api = {
                s1Service.getPostsWrapper(threadId, page, authorId)
            },
            interceptor = interceptor,
            keys = cacheKeys,
            groupsExtra = cacheExtraGroups
        )

        fun saveCache(postWrapper: PostsWrapper) {
            if (cacheValid && isPostsWrapperValid(postWrapper)) {
                cacheBiz.saveZipAsync(
                    apiCacheFlow.getKey(
                        cacheType,
                        cacheKeys
                    ),
                    user.uid?.toIntOrNull(),
                    postWrapper,
                    maxSize = downloadPerf.totalDataCacheSize,
                    groups = cacheGroups
                )
                // 保存title时不保存page
                postWrapper.data?.postListInfo?.let { thread ->
                    thread.title?.apply {
                        cacheGroupBiz.saveTitleAsync(
                            this,
                            groups = groupGroups,
                            extras = listOf(thread.reliesCount.toString())
                        )
                    }
                }
            }
        }

        // 评分缓存过期的回帖，先展示缓存，然后再刷新
        val outdatedRatePostIds = mutableListOf<Int>()
        return apiCacheFlow.getFlow()
            .combineTransform(ratePostFlow) { it, ratePostWrapper ->
                if (it.source.isCloud() && ratePostWrapper.source.isCloud()) {
                    withContext(Dispatchers.IO) {
                        var hasError = false
                        val postWrapper = it.data
                        ratePostWrapper.apply {
                            if (this.isError) {
                                hasError = true
                            }
                        }

                        //初始化评分数量信息
                        ratePostWrapper.data?.data?.commentCountMap?.apply {
                            postWrapper?.data?.initCommentCount(this)
                        }

                        // 交易帖
                        suspend fun doTrade(postList: List<Post>) {
                            val post = postList[0]
                            if (post.isTrade) {
                                post.extraHtml = ""
                                runCatching {
                                    loadTime.run("get_post_trade_info") {
                                        s1Service.getTradePostInfo(threadId, post.id + 1)
                                    }.apply {
                                        post.extraHtml = ApiUtil.replaceAjaxHeader(this)
                                    }
                                }.apply {
                                    if (this.isFailure) {
                                        hasError = true
                                    }
                                }
                            }
                        }

                        //从内存缓存中获取评分详情。
                        suspend fun loadRatesFromCache(it: Post) {
                            val cacheKey = getRateKey(threadId, it.id)
                            val cache = ratesCache[cacheKey]

                            if (cache != null) {
                                val rates = Rate.blacklist(blackListBiz, cache.data)
                                it.rates = rates
                                if (System.currentTimeMillis() - cache.time > CACHE_RATE_MILLS) {
                                    outdatedRatePostIds.add(it.id)
                                }
                            }
                        }

                        val postList = postWrapper?.data?.postList
                        if (!postList.isNullOrEmpty()) {
                            doTrade(postList)

                            postList.filter { it.rates?.size == 0 }.forEach {
                                loadRatesFromCache(it)
                            }
                        }
                        if (!hasError && postWrapper != null && cacheValid) {
                            withContext(Dispatchers.Default) {
                                loadTime.run(ApiCacheConstants.Time.TIME_SAVE_CACHE) {
                                    saveCache(postWrapper)
                                }
                            }
                        }
                    }

                    val postsWrapper = it.data
                    // 获取评分详情。
                    if (onRateUpdate != null && postsWrapper != null) {
                        coroutineScope {
                            launch {
                                val posts = postsWrapper.data?.postList
                                if (posts != null) {
                                    withContext(Dispatchers.IO) {
                                        posts.filter {
                                            it.rates?.size == 0 || outdatedRatePostIds.contains(
                                                it.id
                                            )
                                        }
                                            .distinctBy { it.id }
                                            .asFlow()
                                            .map {
                                                val pid = it.id
                                                val rates = loadTime.run("get_rate_${pid}") {
                                                    getPostRates(
                                                        threadId,
                                                        pid
                                                    ).data
                                                }

                                                // 最新数据和缓存不同时，才刷新
                                                if (rates != null && it.rates != rates) {
                                                    it.rates = rates
                                                    launch(Dispatchers.Main) {
                                                        onRateUpdate(pid, rates)
                                                    }
                                                    pid
                                                } else {
                                                    null
                                                }
                                            }.debounce(CACHE_RATE_SAVE_DEBOUNCE)
                                            .collect {
                                                if (cacheValid && it != null) {
                                                    withContext(Dispatchers.Default) {
                                                        loadTime.run(ApiCacheConstants.Time.TIME_UPDATE_CACHE + it) {
                                                            saveCache(postsWrapper)
                                                        }
                                                    }
                                                }
                                            }
                                    }
                                }
                            }
                        }
                    }

                    emit(it)
                } else if (it.source.isCache() && ratePostWrapper.source.isCache()) {
                    emit(it)
                }
            }.onCompletion {
                loadTime.addPoint("completion")
                if (BuildConfig.DEBUG) {
                    loadTime.addPoint(ApiCacheConstants.Time.TIME_LOAD_END)
                    L.i(
                        TAG,
                        "posts:$threadId#$page ${jsonMapper.writeValueAsString(loadTime.times)}"
                    )
                }
            }
    }

    private fun getRateKey(threadId: String?, pid: Int): String {
        return "u${user.uid ?: ""}#${threadId ?: ""}#$pid"
    }

    private fun getRatePostsWrapper(
        threadId: String,
        page: Int,
        authorId: String?,
        cacheParam: CacheParam,
    ): Flow<Resource<RatePostsWrapper>> {
        val cacheType = ApiCacheConstants.CacheType.PostsNew
        val cacheKeys = listOf(threadId, page)
        val interceptor = ApiCacheValidatorCache<RatePostsWrapper> {
            (it.data?.postList?.size ?: 0) > 0
        }
        val apiCacheFlow = ApiCacheFlow(
            downloadPerf, cacheBiz, user, jsonMapper,
            cacheType,
            cacheParam,
            RatePostsWrapper::class.java,
            api = {
                s1Service.getPostsWrapperNew(threadId, page, authorId)
            },
            interceptor = interceptor,
            keys = cacheKeys
        )
        return apiCacheFlow.getFlow()
    }

    override suspend fun getPostRates(threadId: String, postId: Int): Resource<List<Rate>> {
        val rate = runCatching {
            s1Service.getRates(threadId, postId.toString()).let {
                Rate.fromHtml(it)
            }.apply {
                ratesCache.put(
                    getRateKey(threadId, postId),
                    BaseCache(System.currentTimeMillis(), this)
                )
            }.let {
                Rate.blacklist(blackListBiz, it)
            }
        }
        return Resource.fromResult(Source.CLOUD, rate)
    }

    companion object {
        const val TAG = "S1ApiCache"
        const val CACHE_RATE_MILLS = 30_000L
        const val CACHE_RATE_SAVE_DEBOUNCE = 1_000L
    }
}