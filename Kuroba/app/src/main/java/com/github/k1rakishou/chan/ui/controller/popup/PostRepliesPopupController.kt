package com.github.k1rakishou.chan.ui.controller.popup

import android.content.Context
import android.util.LruCache
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.k1rakishou.chan.R
import com.github.k1rakishou.chan.core.di.component.activity.ActivityComponent
import com.github.k1rakishou.chan.ui.adapter.PostRepliesAdapter
import com.github.k1rakishou.chan.ui.cell.PostCellData
import com.github.k1rakishou.chan.ui.cell.PostCellInterface
import com.github.k1rakishou.chan.ui.helper.PostPopupHelper
import com.github.k1rakishou.chan.utils.AppModuleAndroidUtils
import com.github.k1rakishou.chan.utils.BackgroundUtils
import com.github.k1rakishou.chan.utils.RecyclerUtils
import com.github.k1rakishou.chan.utils.RecyclerUtils.restoreScrollPosition
import com.github.k1rakishou.chan.utils.awaitUntilGloballyLaidOutAndGetSize
import com.github.k1rakishou.common.mutableListWithCap
import com.github.k1rakishou.core_logger.Logger
import com.github.k1rakishou.model.data.descriptor.ChanDescriptor
import com.github.k1rakishou.model.data.descriptor.PostDescriptor
import com.github.k1rakishou.model.data.post.ChanPost
import com.github.k1rakishou.model.data.post.PostIndexed
import com.github.k1rakishou.persist_state.IndexAndTop
import kotlinx.coroutines.launch

class PostRepliesPopupController(
  context: Context,
  postPopupHelper: PostPopupHelper,
  postCellCallback: PostCellInterface.PostCellCallback
) : BasePostPopupController<PostRepliesPopupController.PostRepliesPopupData>(context, postPopupHelper, postCellCallback) {
  private var dataView: ViewGroup? = null

  override var displayingData: PostRepliesPopupData? = null

  private val scrollListener = object : RecyclerView.OnScrollListener() {
    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
      super.onScrollStateChanged(recyclerView, newState)

      if (newState == RecyclerView.SCROLL_STATE_IDLE) {
        storeScrollPosition()
      }
    }
  }

  override val postPopupType: PostPopupType
    get() = PostPopupType.Replies

  override fun onDestroy() {
    super.onDestroy()

    dataView = null

    if (postsViewInitialized) {
      postsView.removeOnScrollListener(scrollListener)
    }
  }

  override fun cleanup() {
    if (postsViewInitialized) {
      (postsView.adapter as? PostRepliesAdapter)?.cleanup()
    }
  }

  override fun getDisplayingPostDescriptors(): List<PostDescriptor> {
    if (displayingData == null) {
      return emptyList()
    }

    val postDescriptors: MutableList<PostDescriptor> = ArrayList()
    for (chanPost in displayingData!!.posts) {
      postDescriptors.add(chanPost.postDescriptor)
    }

    return postDescriptors
  }

  override fun injectActivityDependencies(component: ActivityComponent) {
    component.inject(this)
  }

  override suspend fun displayData(
    chanDescriptor: ChanDescriptor,
    data: PostRepliesPopupData
  ): ViewGroup {
    BackgroundUtils.ensureMainThread()

    val repliesAdapter = getRepliesAdapterOrInitializeEverything(data, chanDescriptor)
    repliesAdapter.init(data.forPostWithDescriptor)

    controllerScope.launch {
      val (width, _) = postsView.awaitUntilGloballyLaidOutAndGetSize(waitForWidth = true)

      val retainedPosts = postHideHelper.get().processPostFilters(chanDescriptor, data.posts, mutableSetOf())
        .safeUnwrap { error ->
          Logger.e(TAG, "postHideHelper.filterHiddenPosts error", error)
          return@launch
        }

      val indexedPosts = indexPosts(chanDescriptor, retainedPosts)
      repliesAdapter.setOrUpdateData(
        postCellDataWidthNoPaddings = width,
        postIndexedList = indexedPosts,
        theme = themeEngine.chanTheme
      )

      restoreScrollPosition(data.forPostWithDescriptor)
    }

    return dataView!!
  }

  private fun getRepliesAdapterOrInitializeEverything(
    data: PostRepliesPopupData,
    chanDescriptor: ChanDescriptor
  ): PostRepliesAdapter {
    if (postsViewInitialized && postsView.adapter is PostRepliesAdapter && dataView != null) {
      return postsView.adapter as PostRepliesAdapter
    }

    val dataView = AppModuleAndroidUtils.inflate(context, R.layout.layout_post_popup_replies)
    dataView.id = R.id.post_popup_replies_view_id
    postsView = dataView.findViewById(R.id.post_list)

    val repliesAdapter = PostRepliesAdapter(
      recyclerView = postsView,
      postViewMode = data.postViewMode,
      postCellCallback = postCellCallback,
      chanDescriptor = chanDescriptor,
      _chanThreadViewableInfoManager = chanThreadViewableInfoManager,
      _chanThreadManager = chanThreadManager,
      _postFilterManager = postFilterManager,
      _savedReplyManager = savedReplyManager,
      _postFilterHighlightManager = postFilterHighlightManager,
      _postHideManager = postHideManager,
      initialTheme = themeEngine.chanTheme
    )

    repliesAdapter.setHasStableIds(true)

    postsView.layoutManager = LinearLayoutManager(context)
    postsView.recycledViewPool.setMaxRecycledViews(PostRepliesAdapter.POST_REPLY_VIEW_TYPE, 0)
    postsView.itemAnimator = null
    postsView.adapter = repliesAdapter
    postsView.addOnScrollListener(scrollListener)

    this.dataView = dataView

    return repliesAdapter
  }

  private fun indexPosts(
    chanDescriptor: ChanDescriptor,
    retainedPosts: List<ChanPost>
  ): List<PostIndexed> {
    if (retainedPosts.isEmpty()) {
      return emptyList()
    }

    val postIndexedList = mutableListWithCap<PostIndexed>(retainedPosts.size)

    if (chanDescriptor is ChanDescriptor.ICatalogDescriptor) {
      return retainedPosts.mapIndexed { index, chanPost ->
        return@mapIndexed PostIndexed(chanPost, index)
      }
    }

    chanDescriptor as ChanDescriptor.ThreadDescriptor

    chanThreadManager.get().iteratePostIndexes(
      threadDescriptor = chanDescriptor,
      input = retainedPosts,
      postDescriptorSelector = ChanPost::postDescriptor
    ) { chanPost, postIndex ->
      postIndexedList.add(PostIndexed(chanPost, postIndex))
    }

    return postIndexedList
  }

  override fun onImageIsAboutToShowUp() {
    // no-op
  }

  private fun storeScrollPosition() {
    if (!postsViewInitialized) {
      return
    }

    val firstPostDescriptor = displayingData?.forPostWithDescriptor
    if (firstPostDescriptor == null) {
      return
    }

    scrollPositionCache.put(
      firstPostDescriptor,
      RecyclerUtils.getIndexAndTop(postsView)
    )
  }

  private fun restoreScrollPosition(postDescriptor: PostDescriptor) {
    if (!postsViewInitialized) {
      return
    }

    val scrollPosition = scrollPositionCache[postDescriptor]
      ?: return

    postsView.restoreScrollPosition(scrollPosition)
  }

  class PostRepliesPopupData(
    override val descriptor: ChanDescriptor,
    override val postViewMode: PostCellData.PostViewMode,
    val forPostWithDescriptor: PostDescriptor,
    val posts: List<ChanPost>
  ) : PostPopupHelper.PostPopupData

  companion object {
    private const val TAG = "PostRepliesPopupController"
    val scrollPositionCache = LruCache<PostDescriptor, IndexAndTop>(128)
  }

}