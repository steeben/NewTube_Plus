package com.liskovsoft.smartyoutubetv2.common.misc;

import android.content.Context;
import android.util.Pair;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.mediaserviceinterfaces.ServiceManager;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.rx.RxHelper;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.prefs.MainUIData;
import com.liskovsoft.smartyoutubetv2.common.prefs.common.DataChangeBase.OnDataChange;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

public class UnlocalizedTitleProcessor implements OnDataChange, BrowseProcessor {
    private static final String TAG = UnlocalizedTitleProcessor.class.getSimpleName();
    private final OnItemReady mOnItemReady;
    private final MediaItemService mItemService;
    private final MainUIData mMainUIData;
    private boolean mIsUnlocalizedTitlesEnabled;
    private Disposable mResult;

    public UnlocalizedTitleProcessor(Context context, OnItemReady onItemReady) {
        mOnItemReady = onItemReady;
        ServiceManager service = YouTubeServiceManager.instance();
        mItemService = service.getMediaItemService();
        mMainUIData = MainUIData.instance(context);
        mMainUIData.setOnChange(this);
        initData();
    }

    @Override
    public void onDataChange() {
        initData();
    }

    private void initData() {
        mIsUnlocalizedTitlesEnabled = mMainUIData.isUnlocalizedTitlesEnabled();
    }

    @Override
    public void process(VideoGroup videoGroup) {
        if (!mIsUnlocalizedTitlesEnabled || videoGroup == null || videoGroup.isEmpty()) {
            return;
        }

        List<String> videoIds = getVideoIds(videoGroup);
        mResult = Observable.fromIterable(videoIds)
                .flatMap(videoId -> mItemService.getUnlocalizedTitleObserve(videoId)
                        .map(newTitle -> new Pair<>(videoId, newTitle))
                        // NEWTUBE(#45): one video without an oEmbed title (embedding off, private:
                        // 401 -> null -> error) used to end the whole stream, so most cards kept
                        // YouTube's translation. Skip that video and keep the rest.
                        .onErrorResumeNext(error -> {
                            Log.d(TAG, "Unlocalized title: Cannot process the video %s", videoId);
                            return Observable.empty();
                        }))
                .subscribe(title -> {
                    Video video = videoGroup.findVideoById(title.first);
                    if (video == null) {
                        return;
                    }
                    // NEWTUBE(#45): its own field, so DeArrow's crowd title (deArrowTitle) still
                    // wins in Video.getTitle() and this is only the fallback under it.
                    video.unlocalizedTitle = title.second; // sometimes player titles localized while cards not
                    if (!Helpers.equals(video.title, video.unlocalizedTitle)) {
                        mOnItemReady.onItemReady(video);
                    }
                },
                error -> {
                    Log.d(TAG, "Unlocalized title: Cannot process the video");
                });
    }

    @Override
    public void dispose() {
        RxHelper.disposeActions(mResult);
    }

    private List<String> getVideoIds(VideoGroup videoGroup) {
        return claimVideoIds(videoGroup.getVideos());
    }

    /**
     * Ids of the videos whose original title hasn't been fetched yet, marking them as fetched.
     * NEWTUBE(#45): its own flag - sharing DeArrow's meant DeArrow (first in BrowseProcessorManager)
     * claimed every video and this step never ran while any DeArrow option was on.
     */
    static List<String> claimVideoIds(List<Video> videos) {
        List<String> result = new ArrayList<>();

        for (Video video : videos) {
            // A channel/playlist card has no video id: a null in fromIterable() ended the whole
            // stream, so a search that starts with a channel got no original titles at all.
            if (video.unlocalizedTitleProcessed || video.videoId == null) {
                continue;
            }
            video.unlocalizedTitleProcessed = true;
            result.add(video.videoId);
        }

        return result;
    }
}
