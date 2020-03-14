package fr.gaulupeau.apps.Poche.ui;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.EnumSet;

import fr.gaulupeau.apps.InThePoche.R;
import fr.gaulupeau.apps.Poche.data.DbConnection;
import fr.gaulupeau.apps.Poche.data.dao.ArticleDao;
import fr.gaulupeau.apps.Poche.data.dao.entities.Article;
import fr.gaulupeau.apps.Poche.events.ArticlesChangedEvent;
import fr.gaulupeau.apps.Poche.events.FeedsChangedEvent;
import fr.gaulupeau.apps.Poche.events.LocalArticleReplacedEvent;
import fr.gaulupeau.apps.Poche.service.OperationsHelper;
import fr.gaulupeau.apps.Poche.service.workers.OperationsWorker;

public class EditAddedArticleActivity extends AppCompatActivity {

    public static final String PARAM_ARTICLE_URL = "article_url";

    private static final String TAG = EditAddedArticleActivity.class.getSimpleName();

    private static final String STATE_DISCOVERED_ARTICLE_ID = "discovered_article_id";
    private static final String STATE_ARCHIVED = "archived";
    private static final String STATE_FAVORITE = "favorite";

    private TextView articleTitleTv;
    private ImageButton favoriteButton;
    private ImageButton archiveButton;
    private ImageButton openButton;

    private Handler handler;
    private Object autocloseToken = new Object();
    private boolean shouldAutoclose = true;

    private String url;
    private int articleId = -1;

    private Article article;

    private boolean archived;
    private boolean favorite;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Themes.applyDialogTheme(this);
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_edit_added_article);

        articleTitleTv = findViewById(R.id.editActivity_articleTitle);
        favoriteButton = findViewById(R.id.editActivity_favoriteButton);
        archiveButton = findViewById(R.id.editActivity_archiveButton);
        openButton = findViewById(R.id.editActivity_openButton);

        if (savedInstanceState != null) {
            articleId = savedInstanceState.getInt(STATE_DISCOVERED_ARTICLE_ID, -1);
            archived = savedInstanceState.getBoolean(STATE_ARCHIVED, false);
            favorite = savedInstanceState.getBoolean(STATE_FAVORITE, false);
        }

        url = getIntent().getStringExtra(PARAM_ARTICLE_URL);

        init();

        scheduleAutoclose();

        EventBus.getDefault().register(this);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putInt(STATE_DISCOVERED_ARTICLE_ID, articleId);
        outState.putBoolean(STATE_ARCHIVED, archived);
        outState.putBoolean(STATE_FAVORITE, favorite);
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);

        cancelAutoclose();

        super.onDestroy();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onArticlesChangedEvent(ArticlesChangedEvent articlesChangedEvent) {
        Log.d(TAG, "onArticlesChangedEvent() started");

        if (articleId == -1) return;

        EnumSet<FeedsChangedEvent.ChangeType> changes
                = articlesChangedEvent.getArticleChanges(articleId);

        if (changes != null) { // TODO: proper check
            init();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onLocalArticleReplacedEvent(LocalArticleReplacedEvent event) {
        Log.e(TAG, "onLocalArticleReplacedEvent() started");

        if (TextUtils.equals(url, event.getGivenUrl())) {
            articleId = event.getArticleId();

            init();
        }
    }

    public void onArchiveClick(View view) {
        cancelAutoclose();

        archived = !archived;

        updateViews();

        OperationsHelper.archiveArticle(this, url, archived);
    }

    public void onFavoriteClick(View view) {
        cancelAutoclose();

        favorite = !favorite;

        updateViews();

        OperationsHelper.favoriteArticle(this, url, favorite);
    }

    public void onTagClick(View view) {
        cancelAutoclose();

        Intent intent = new Intent(this, ManageArticleTagsActivity.class);
        if (articleId != -1) {
            intent.putExtra(ManageArticleTagsActivity.PARAM_ARTICLE_ID, articleId);
        } else {
            intent.putExtra(ManageArticleTagsActivity.PARAM_ARTICLE_URL, url);
        }

        startActivity(intent);
    }

    public void onOpenClick(View view) {
        Intent intent = new Intent(this, ReadArticleActivity.class);
        intent.putExtra(ReadArticleActivity.EXTRA_ID, article.getId());
        startActivity(intent);

        finish();
    }

    private void init() {
        loadArticle();

        updateVars();
        updateViews();
    }

    private void loadArticle() {
        if (articleId != -1) {
            article = DbConnection.getSession().getArticleDao().queryBuilder()
                    .where(ArticleDao.Properties.ArticleId.eq(articleId))
                    .unique();
        } else {
            article = new OperationsWorker(this).findArticleByUrl(url);
        }

        if (articleId == -1 && article != null && article.getArticleId() != null) {
            articleId = article.getArticleId();
        }
    }

    private void updateVars() {
        if (article != null && article.getArticleId() != null) {
            archived = Boolean.TRUE.equals(article.getArchive());
            favorite = Boolean.TRUE.equals(article.getFavorite());
        }
    }

    private void updateViews() {
        String title = article != null && !TextUtils.isEmpty(article.getTitle())
                ? article.getTitle() : url;

        articleTitleTv.setText(title);

        favoriteButton.setImageResource(resolveResource(
                favorite ? R.attr.icon_favorite_undo : R.attr.icon_favorite));
        favoriteButton.setContentDescription(getString(
                favorite ? R.string.remove_from_favorites : R.string.add_to_favorites));

        archiveButton.setImageResource(resolveResource(
                archived ? R.attr.icon_read_undo : R.attr.icon_read));
        archiveButton.setContentDescription(getString(
                archived ? R.string.btnMarkUnread : R.string.btnMarkRead));

        openButton.setEnabled(articleId != -1 && article != null);
    }

    private int resolveResource(int resId) {
        Resources.Theme theme = getTheme();
        TypedValue value = new TypedValue();
        theme.resolveAttribute(resId, value, true);
        return value.resourceId;
    }

    private void scheduleAutoclose() {
        handler = new Handler();
        handler.postDelayed(() -> {
            if (shouldAutoclose) finish();
        }, 7000);
    }

    private void cancelAutoclose() {
        shouldAutoclose = false;
        handler.removeCallbacksAndMessages(autocloseToken);
    }

}