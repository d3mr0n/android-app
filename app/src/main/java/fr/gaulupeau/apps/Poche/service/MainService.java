package fr.gaulupeau.apps.Poche.service;

import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import fr.gaulupeau.apps.Poche.data.QueueHelper;
import fr.gaulupeau.apps.Poche.data.Settings;
import fr.gaulupeau.apps.Poche.data.dao.AnnotationDao;
import fr.gaulupeau.apps.Poche.data.dao.ArticleDao;
import fr.gaulupeau.apps.Poche.data.dao.DaoSession;
import fr.gaulupeau.apps.Poche.data.dao.entities.Annotation;
import fr.gaulupeau.apps.Poche.data.dao.entities.AnnotationRange;
import fr.gaulupeau.apps.Poche.data.dao.entities.Article;
import fr.gaulupeau.apps.Poche.data.dao.entities.Tag;
import fr.gaulupeau.apps.Poche.data.dao.entities.AddLinkItem;
import fr.gaulupeau.apps.Poche.data.dao.entities.AddOrUpdateAnnotationItem;
import fr.gaulupeau.apps.Poche.data.dao.entities.ArticleChangeItem;
import fr.gaulupeau.apps.Poche.data.dao.entities.ArticleTagsDeleteItem;
import fr.gaulupeau.apps.Poche.data.dao.entities.DeleteAnnotationItem;
import fr.gaulupeau.apps.Poche.data.dao.entities.QueueItem;
import fr.gaulupeau.apps.Poche.events.ActionResultEvent;
import fr.gaulupeau.apps.Poche.events.LinkUploadedEvent;
import fr.gaulupeau.apps.Poche.events.ArticlesChangedEvent;
import fr.gaulupeau.apps.Poche.events.OfflineQueueChangedEvent;
import fr.gaulupeau.apps.Poche.events.SweepDeletedArticlesFinishedEvent;
import fr.gaulupeau.apps.Poche.events.SweepDeletedArticlesProgressEvent;
import fr.gaulupeau.apps.Poche.events.SweepDeletedArticlesStartedEvent;
import fr.gaulupeau.apps.Poche.events.SyncQueueFinishedEvent;
import fr.gaulupeau.apps.Poche.events.SyncQueueProgressEvent;
import fr.gaulupeau.apps.Poche.events.SyncQueueStartedEvent;
import fr.gaulupeau.apps.Poche.events.UpdateArticlesProgressEvent;
import fr.gaulupeau.apps.Poche.events.UpdateArticlesStartedEvent;
import fr.gaulupeau.apps.Poche.events.UpdateArticlesFinishedEvent;
import fr.gaulupeau.apps.Poche.network.Updater;
import fr.gaulupeau.apps.Poche.network.WallabagConnection;
import fr.gaulupeau.apps.Poche.network.exceptions.IncorrectConfigurationException;

import wallabag.apiwrapper.ModifyArticleBuilder;
import wallabag.apiwrapper.WallabagService;
import wallabag.apiwrapper.exceptions.UnsuccessfulResponseException;

import static fr.gaulupeau.apps.Poche.events.EventHelper.postEvent;
import static fr.gaulupeau.apps.Poche.events.EventHelper.postStickyEvent;
import static fr.gaulupeau.apps.Poche.events.EventHelper.removeStickyEvent;

public class MainService extends IntentServiceBase {

    private static final String TAG = MainService.class.getSimpleName();

    private Updater updater;

    public MainService() {
        super(MainService.class.getSimpleName());
        setIntentRedelivery(true);

        Log.d(TAG, "MainService() created");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.d(TAG, "onHandleIntent() started");

        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);

        ActionRequest actionRequest = ActionRequest.fromIntent(intent);
        ActionResult result = null;

        switch(actionRequest.getAction()) {
            case ARTICLE_CHANGE:
            case ARTICLE_TAGS_DELETE:
            case ANNOTATION_ADD:
            case ANNOTATION_UPDATE:
            case ANNOTATION_DELETE:
            case ARTICLE_DELETE:
            case ADD_LINK:
                Long queueChangedLength = serveSimpleRequest(actionRequest);
                if(queueChangedLength != null) {
                    postEvent(new OfflineQueueChangedEvent(queueChangedLength, true));
                }
                break;

            case SYNC_QUEUE: {
                SyncQueueStartedEvent startEvent = new SyncQueueStartedEvent(actionRequest);
                postStickyEvent(startEvent);
                Pair<ActionResult, Long> syncResult = null;
                try {
                    syncResult = syncOfflineQueue(actionRequest);
                    result = syncResult.first;
                } finally {
                    removeStickyEvent(startEvent);
                    if(result == null) result = new ActionResult(ActionResult.ErrorType.UNKNOWN);
                    postEvent(new SyncQueueFinishedEvent(actionRequest, result,
                            syncResult != null ? syncResult.second : null));
                }
                break;
            }

            case UPDATE_ARTICLES: {
                UpdateArticlesStartedEvent startEvent = new UpdateArticlesStartedEvent(actionRequest);
                postStickyEvent(startEvent);
                try {
                    result = updateArticles(actionRequest);
                } finally {
                    removeStickyEvent(startEvent);
                    if(result == null) result = new ActionResult(ActionResult.ErrorType.UNKNOWN);
                    postEvent(new UpdateArticlesFinishedEvent(actionRequest, result));
                }
                break;
            }

            case SWEEP_DELETED_ARTICLES: {
                SweepDeletedArticlesStartedEvent startEvent
                        = new SweepDeletedArticlesStartedEvent(actionRequest);
                postStickyEvent(startEvent);
                try {
                    result = sweepDeletedArticles(actionRequest);
                } finally {
                    removeStickyEvent(startEvent);
                    if(result == null) result = new ActionResult(ActionResult.ErrorType.UNKNOWN);
                    postEvent(new SweepDeletedArticlesFinishedEvent(actionRequest, result));
                }
                break;
            }

            default:
                Log.w(TAG, "Unknown action requested: " + actionRequest.getAction());
                break;
        }

        postEvent(new ActionResultEvent(actionRequest, result));

        Log.d(TAG, "onHandleIntent() finished");
    }

    private Long serveSimpleRequest(ActionRequest actionRequest) {
        Log.d(TAG, String.format("serveSimpleRequest() started; action: %s, articleID: %s" +
                        ", extra: %s, extra2: %s",
                actionRequest.getAction(), actionRequest.getArticleID(),
                actionRequest.getExtra(), actionRequest.getExtra2()));

        Long queueChangedLength = null;

        DaoSession daoSession = getDaoSession();
        SQLiteDatabase sqliteDatabase = (SQLiteDatabase)daoSession.getDatabase().getRawDatabase();
        sqliteDatabase.beginTransactionNonExclusive();
        try {
            QueueHelper queueHelper = new QueueHelper(daoSession);

            ActionRequest.Action action = actionRequest.getAction();
            switch(action) {
                case ARTICLE_CHANGE:
                    if(queueHelper.changeArticle(actionRequest.getArticleID(),
                            actionRequest.getArticleChangeType())) {
                        queueChangedLength = queueHelper.getQueueLength();
                    }
                    break;

                case ARTICLE_TAGS_DELETE:
                    if(queueHelper.deleteTagsFromArticle(actionRequest.getArticleID(),
                            actionRequest.getExtra())) {
                        queueChangedLength = queueHelper.getQueueLength();
                    }
                    break;

                case ANNOTATION_ADD:
                    if (queueHelper.addAnnotationToArticle(actionRequest.getArticleID(),
                            Long.parseLong(actionRequest.getExtra()))) {
                        queueChangedLength = queueHelper.getQueueLength();
                    }
                    break;

                case ANNOTATION_UPDATE:
                    if (queueHelper.updateAnnotationOnArticle(actionRequest.getArticleID(),
                            Long.parseLong(actionRequest.getExtra()))) {
                        queueChangedLength = queueHelper.getQueueLength();
                    }
                    break;

                case ANNOTATION_DELETE:
                    if (queueHelper.deleteAnnotationFromArticle(actionRequest.getArticleID(),
                            Integer.parseInt(actionRequest.getExtra()))) {
                        queueChangedLength = queueHelper.getQueueLength();
                    }
                    break;

                case ARTICLE_DELETE:
                    if(queueHelper.deleteArticle(actionRequest.getArticleID())) {
                        queueChangedLength = queueHelper.getQueueLength();
                    }
                    break;

                case ADD_LINK:
                    if(queueHelper.addLink(actionRequest.getExtra(), actionRequest.getExtra2())) {
                        queueChangedLength = queueHelper.getQueueLength();
                    }
                    break;

                default:
                    Log.w(TAG, "serveSimpleRequest() action is not implemented: " + action);
                    break;
            }

            sqliteDatabase.setTransactionSuccessful();
        } finally {
            sqliteDatabase.endTransaction();
        }

        Log.d(TAG, "serveSimpleRequest() finished");
        return queueChangedLength;
    }

    private Pair<ActionResult, Long> syncOfflineQueue(ActionRequest actionRequest) {
        Log.d(TAG, "syncOfflineQueue() started");

        if(!WallabagConnection.isNetworkAvailable()) {
            Log.i(TAG, "syncOfflineQueue() not on-line; exiting");
            return new Pair<>(new ActionResult(ActionResult.ErrorType.NO_NETWORK), null);
        }

        ActionResult result = new ActionResult();
        boolean urlUploaded = false;

        DaoSession daoSession = getDaoSession();
        QueueHelper queueHelper = new QueueHelper(daoSession);

        List<QueueItem> queueItems = queueHelper.getQueueItems();

        List<QueueItem> completedQueueItems = new ArrayList<>(queueItems.size());

        int counter = 0, totalNumber = queueItems.size();
        for(QueueItem item: queueItems) {
            Log.d(TAG, "syncOfflineQueue() current QueueItem(" + (counter+1) + " out of " + totalNumber+ "): " + item);
            postEvent(new SyncQueueProgressEvent(actionRequest, counter, totalNumber));

            Integer articleIdInteger = item.getArticleId();

            Log.d(TAG, String.format(
                    "syncOfflineQueue() processing: queue item ID: %d, article ID: \"%s\"",
                    item.getId(), articleIdInteger));

            int articleID = articleIdInteger != null ? articleIdInteger : -1;

            boolean canTolerateNotFound = true;

            ActionResult itemResult = null;
            try {
                switch (item.getAction()) {
                    case ARTICLE_CHANGE:
                        itemResult = syncArticleChange(item.asSpecificItem(), articleID);
                        break;

                    case ARTICLE_TAGS_DELETE:
                        itemResult = syncDeleteTagsFromArticle(item.asSpecificItem(), articleID);
                        break;

                    case ANNOTATION_ADD:
                        itemResult = syncAddAnnotationToArticle(item.asSpecificItem(), articleID);
                        break;

                    case ANNOTATION_UPDATE:
                        itemResult = syncUpdateAnnotationOnArticle(item.asSpecificItem(), articleID);
                        break;

                    case ANNOTATION_DELETE:
                        itemResult = syncDeleteAnnotationFromArticle(item.asSpecificItem(), articleID);
                        break;

                    case ARTICLE_DELETE:
                        if (!getWallabagService().deleteArticle(articleID)) {
                            itemResult = new ActionResult(ActionResult.ErrorType.NOT_FOUND);
                        }
                        break;

                    case ADD_LINK: {
                        canTolerateNotFound = false;

                        AddLinkItem addLinkItem = item.asSpecificItem();
                        String link = addLinkItem.getUrl();
                        String origin = addLinkItem.getOrigin();
                        Log.d(TAG, "syncOfflineQueue() action ADD_LINK link=" + link
                                + ", origin=" + origin);

                        if (!TextUtils.isEmpty(link)) {
                            getWallabagService()
                                    .addArticleBuilder(link)
                                    .originUrl(origin)
                                    .execute();
                            urlUploaded = true;
                        } else {
                            Log.w(TAG, "syncOfflineQueue() action is ADD_LINK, but item has no link; skipping");
                        }
                        break;
                    }

                    default:
                        throw new IllegalArgumentException("Unknown action: " + item.getAction());
                }
            } catch(IncorrectConfigurationException | UnsuccessfulResponseException
                    | IOException | IllegalArgumentException e) {
                ActionResult r = processException(e, "syncOfflineQueue()");
                if(!r.isSuccess()) itemResult = r;
            } catch(Exception e) {
                Log.e(TAG, "syncOfflineQueue() item processing exception", e);

                itemResult = new ActionResult(ActionResult.ErrorType.UNKNOWN, e);
            }

            if(itemResult != null && !itemResult.isSuccess() && canTolerateNotFound
                    && itemResult.getErrorType() == ActionResult.ErrorType.NOT_FOUND) {
                Log.i(TAG, "syncOfflineQueue() ignoring NOT_FOUND");
                itemResult = null;
            }

            if(itemResult == null || itemResult.isSuccess()) {
                completedQueueItems.add(item);
            } else if(itemResult.getErrorType() != null) {
                ActionResult.ErrorType itemError = itemResult.getErrorType();

                Log.i(TAG, "syncOfflineQueue() itemError: " + itemError);

                boolean stop = true;
                switch(itemError) {
                    case NOT_FOUND_LOCALLY:
                    case NEGATIVE_RESPONSE:
                        stop = false;
                        break;
                }

                if(stop) {
                    result.updateWith(itemResult);
                    Log.i(TAG, "syncOfflineQueue() the itemError is a showstopper; breaking");
                    break;
                }
            } else { // should not happen
                Log.w(TAG, "syncOfflineQueue() errorType is not present in itemResult");
            }

            Log.d(TAG, "syncOfflineQueue() finished processing queue item");
        }

        Long queueLength = null;

        if(!completedQueueItems.isEmpty()) {
            SQLiteDatabase sqliteDatabase = (SQLiteDatabase)daoSession.getDatabase().getRawDatabase();
            sqliteDatabase.beginTransactionNonExclusive();
            try {
                queueHelper.dequeueItems(completedQueueItems);

                queueLength = queueHelper.getQueueLength();

                sqliteDatabase.setTransactionSuccessful();
            } finally {
                sqliteDatabase.endTransaction();
            }
        }

        if(queueLength != null) {
            postEvent(new OfflineQueueChangedEvent(queueLength));
        } else {
            queueLength = (long)queueItems.size();
        }

        if(urlUploaded) {
            postEvent(new LinkUploadedEvent(new ActionResult()));
        }

        Log.d(TAG, "syncOfflineQueue() finished");
        return new Pair<>(result, queueLength);
    }

    private ActionResult syncArticleChange(ArticleChangeItem item, int articleID)
            throws IncorrectConfigurationException, UnsuccessfulResponseException, IOException {
        Article article = getDaoSession().getArticleDao().queryBuilder()
                .where(ArticleDao.Properties.ArticleId.eq(articleID)).unique();

        if (article == null) {
            return new ActionResult(ActionResult.ErrorType.NOT_FOUND_LOCALLY,
                    "Article is not found locally");
        }

        ModifyArticleBuilder builder = getWallabagService()
                .modifyArticleBuilder(articleID);

        for (QueueItem.ArticleChangeType changeType : item.getArticleChanges()) {
            switch (changeType) {
                case ARCHIVE:
                    builder.archive(article.getArchive());
                    break;

                case FAVORITE:
                    builder.starred(article.getFavorite());
                    break;

                case TITLE:
                    builder.title(article.getTitle());
                    break;

                case TAGS:
                    // all tags are pushed
                    for (Tag tag : article.getTags()) {
                        builder.tag(tag.getLabel());
                    }
                    break;

                default:
                    throw new IllegalStateException("Change type is not implemented: " + changeType);
            }
        }

        ActionResult itemResult = null;

        if (builder.execute() == null) {
            itemResult = new ActionResult(ActionResult.ErrorType.NOT_FOUND);
        }

        return itemResult;
    }

    private ActionResult syncDeleteTagsFromArticle(ArticleTagsDeleteItem item, int articleID)
            throws IncorrectConfigurationException, UnsuccessfulResponseException, IOException {
        WallabagService wallabagService = getWallabagService();

        ActionResult itemResult = null;

        for (String tag : item.getTagIds()) {
            if (wallabagService.deleteTag(articleID, Integer.parseInt(tag)) == null
                    && itemResult == null) {
                itemResult = new ActionResult(ActionResult.ErrorType.NOT_FOUND);
            }
        }

        return itemResult;
    }

    private ActionResult syncAddAnnotationToArticle(AddOrUpdateAnnotationItem item, int articleId)
            throws IncorrectConfigurationException, UnsuccessfulResponseException, IOException {
        AnnotationDao annotationDao = getDaoSession().getAnnotationDao();
        Annotation annotation = annotationDao.queryBuilder()
                .where(AnnotationDao.Properties.Id.eq(item.getLocalAnnotationId())).unique();

        if (annotation == null) {
            return new ActionResult(ActionResult.ErrorType.NOT_FOUND_LOCALLY,
                    "Annotation wasn't found locally");
        }

        List<wallabag.apiwrapper.models.Annotation.Range> ranges
                = new ArrayList<>(annotation.getRanges().size());
        for (AnnotationRange range : annotation.getRanges()) {
            wallabag.apiwrapper.models.Annotation.Range apiRange
                    = new wallabag.apiwrapper.models.Annotation.Range();

            apiRange.start = range.getStart();
            apiRange.end = range.getEnd();
            apiRange.startOffset = range.getStartOffset();
            apiRange.endOffset = range.getEndOffset();

            ranges.add(apiRange);
        }

        wallabag.apiwrapper.models.Annotation remoteAnnotation = getWallabagService()
                .addAnnotation(articleId, ranges, annotation.getText(), annotation.getQuote());

        if (remoteAnnotation == null) {
            Log.w(TAG, String.format("Couldn't add annotation %s to article %d" +
                            ": article wasn't found on server",
                    annotation, articleId));
            return new ActionResult(ActionResult.ErrorType.NOT_FOUND);
        }

        annotation.setAnnotationId(remoteAnnotation.id);

        Log.d(TAG, "syncAddAnnotationToArticle() updating annotation with remote ID: "
                + annotation.getAnnotationId());
        annotationDao.update(annotation);
        Log.d(TAG, "syncAddAnnotationToArticle() updated annotation with remote ID");

        return null;
    }

    private ActionResult syncUpdateAnnotationOnArticle(AddOrUpdateAnnotationItem item, int articleId)
            throws IncorrectConfigurationException, UnsuccessfulResponseException, IOException {
        Annotation annotation = getDaoSession().getAnnotationDao().queryBuilder()
                .where(AnnotationDao.Properties.Id.eq(item.getLocalAnnotationId())).unique();

        if (annotation == null) {
            return new ActionResult(ActionResult.ErrorType.NOT_FOUND_LOCALLY,
                    "Annotation wasn't found locally");
        }
        if (annotation.getAnnotationId() == null) {
            Log.w(TAG, "syncUpdateAnnotationOnArticle() annotation ID is null!");
            return null;
        }

        if (getWallabagService()
                .updateAnnotation(annotation.getAnnotationId(), annotation.getText()) == null) {
            Log.w(TAG, String.format("Couldn't update annotation %s on article %d" +
                            ": not found remotely",
                    annotation, articleId));
            return new ActionResult(ActionResult.ErrorType.NOT_FOUND);
        }

        return null;
    }

    private ActionResult syncDeleteAnnotationFromArticle(DeleteAnnotationItem item, int articleId)
            throws IncorrectConfigurationException, UnsuccessfulResponseException, IOException {
        if (getWallabagService().deleteAnnotation(item.getRemoteAnnotationId()) == null) {
            Log.w(TAG, String.format("Couldn't remove annotationId %d from article %d",
                    item.getRemoteAnnotationId(), articleId));
            return new ActionResult(ActionResult.ErrorType.NOT_FOUND);
        }

        return null;
    }

    private ActionResult updateArticles(final ActionRequest actionRequest) {
        Updater.UpdateType updateType = actionRequest.getUpdateType();
        Log.d(TAG, String.format("updateArticles(%s) started", updateType));

        ActionResult result = new ActionResult();
        ArticlesChangedEvent event = null;

        if(WallabagConnection.isNetworkAvailable()) {
            final Settings settings = getSettings();

            try {
                Updater.UpdateListener updateListener = new Updater.UpdateListener() {
                    @Override
                    public void onProgress(int current, int total) {
                        postEvent(new UpdateArticlesProgressEvent(
                                actionRequest, current, total));
                    }

                    @Override
                    public void onSuccess(long latestUpdatedItemTimestamp) {
                        Log.i(TAG, "updateArticles() update successful, saving timestamps");

                        settings.setLatestUpdatedItemTimestamp(latestUpdatedItemTimestamp);
                        settings.setLatestUpdateRunTimestamp(System.currentTimeMillis());
                        settings.setFirstSyncDone(true);
                    }
                };

                event = getUpdater().update(updateType,
                        settings.getLatestUpdatedItemTimestamp(), updateListener);
            } catch(UnsuccessfulResponseException | IOException e) {
                ActionResult r = processException(e, "updateArticles()");
                result.updateWith(r);
            } catch(Exception e) {
                Log.e(TAG, "updateArticles() exception", e);

                result.setErrorType(ActionResult.ErrorType.UNKNOWN);
                result.setMessage(e.toString());
                result.setException(e);
            }
        } else {
            result.setErrorType(ActionResult.ErrorType.NO_NETWORK);
        }

        if(event != null && event.isAnythingChanged()) {
            postEvent(event);
        }

        Log.d(TAG, "updateArticles() finished");
        return result;
    }

    private ActionResult sweepDeletedArticles(final ActionRequest actionRequest) {
        Log.d(TAG, "sweepDeletedArticles() started");

        ActionResult result = new ActionResult();
        ArticlesChangedEvent event = null;

        if(WallabagConnection.isNetworkAvailable()) {
            try {
                Updater.ProgressListener progressListener = (current, total) ->
                        postEvent(new SweepDeletedArticlesProgressEvent(
                                actionRequest, current, total));

                event = getUpdater().sweepDeletedArticles(progressListener);
            } catch(UnsuccessfulResponseException | IOException e) {
                ActionResult r = processException(e, "sweepDeletedArticles()");
                result.updateWith(r);
            } catch(Exception e) {
                Log.e(TAG, "sweepDeletedArticles() exception", e);

                result.setErrorType(ActionResult.ErrorType.UNKNOWN);
                result.setMessage(e.toString());
                result.setException(e);
            }
        } else {
            result.setErrorType(ActionResult.ErrorType.NO_NETWORK);
        }

        if(event != null && event.isAnythingChanged()) {
            postEvent(event);
        }

        Log.d(TAG, "sweepDeletedArticles() finished");
        return result;
    }

    private Updater getUpdater() throws IncorrectConfigurationException {
        if(updater == null) {
            updater = new Updater(getDaoSession(), getWallabagService());
        }

        return updater;
    }

}
