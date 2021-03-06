package net.bicou.redmine.app.issues;

import android.app.AlertDialog;
import android.app.SearchManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.NavUtils;
import android.support.v7.app.ActionBar;
import android.support.v7.widget.SearchView;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.Window;

import com.google.analytics.tracking.android.EasyTracker;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ipaulpro.afilechooser.FileChooserActivity;
import com.ipaulpro.afilechooser.utils.FileUtils;

import net.bicou.redmine.Constants;
import net.bicou.redmine.R;
import net.bicou.redmine.app.AsyncTaskFragment;
import net.bicou.redmine.app.issues.edit.EditIssueActivity;
import net.bicou.redmine.app.issues.edit.IssuePickerFragment;
import net.bicou.redmine.app.issues.edit.IssueUploader;
import net.bicou.redmine.app.issues.edit.ServerProjectPickerDialog;
import net.bicou.redmine.app.issues.edit.ServerProjectPickerFragment;
import net.bicou.redmine.app.issues.issue.IssueAttachmentsFragment;
import net.bicou.redmine.app.issues.issue.IssueFragment;
import net.bicou.redmine.app.issues.issue.IssueOverviewFragment;
import net.bicou.redmine.app.issues.order.IssuesOrder;
import net.bicou.redmine.app.issues.order.IssuesOrderingFragment;
import net.bicou.redmine.app.issues.order.IssuesOrderingFragment.IssuesOrderSelectionListener;
import net.bicou.redmine.app.misc.EmptyFragment;
import net.bicou.redmine.data.Server;
import net.bicou.redmine.data.json.Attachment;
import net.bicou.redmine.data.json.FileUpload;
import net.bicou.redmine.data.json.Issue;
import net.bicou.redmine.data.json.Project;
import net.bicou.redmine.data.json.Query;
import net.bicou.redmine.data.sqlite.IssuesDbAdapter;
import net.bicou.redmine.data.sqlite.ProjectsDbAdapter;
import net.bicou.redmine.data.sqlite.QueriesDbAdapter;
import net.bicou.redmine.data.sqlite.ServersDbAdapter;
import net.bicou.redmine.net.JsonNetworkError;
import net.bicou.redmine.net.upload.FileUploader;
import net.bicou.redmine.sync.IssuesSyncAdapterService;
import net.bicou.redmine.util.L;
import net.bicou.redmine.util.Util;
import net.bicou.splitactivity.SplitActivity;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

import de.keyboardsurfer.android.widget.crouton.Crouton;
import de.keyboardsurfer.android.widget.crouton.Style;

public class IssuesActivity extends SplitActivity<IssuesListFragment, IssueFragment> implements AsyncTaskFragment.TaskFragmentCallbacks,
        ServerProjectPickerFragment.ServerProjectSelectionListener, IssuePickerFragment.IssueSelectionListener, IssueOverviewFragment.IssueAttachmentLoadListener {
    // Both of these are used for file uploading
    public static final int REQUEST_FILE_CHOOSER = 1337;
    // Constants for the async task fragment:
    public static final int ACTION_REFRESH_ISSUES = 0;
    public static final int ACTION_ISSUE_LOAD_ISSUE = 1;
    public static final int ACTION_ISSUE_LOAD_OVERVIEW = 2;
    public static final int ACTION_ISSUE_LOAD_ATTACHMENTS = 3;
    public static final int ACTION_DELETE_ISSUE = 4;
    public static final int ACTION_UPLOAD_ISSUE = 5;
    public static final int ACTION_ISSUE_TOGGLE_FAVORITE = 6;
    public static final int ACTION_GET_NAVIGATION_SPINNER_DATA = 7;
    public static final int ACTION_UPLOAD_FILE = 8;
    public static final int ACTION_UPLOAD_FILES = 9;
    public static final int ACTION_LINK_UPLOADED_FILES_TO_ISSUE = 10;
    private static final String EXTRA_FILE_PATH = "net.bicou.redmine.app.issues.UploadFilePath";
    private static final String EXTRA_FILES_URI = "net.bicou.redmine.app.issues.UploadFilesUri";
    private static final String EXTRA_FILES_CROUTONS = "net.bicou.redmine.app.issues.UploadFilesCroutons";
    private static final Type FILE_UPLOAD_LIST_TYPE = new TypeToken<List<FileUpload>>() {
    }.getType();
    int mNavMode;
    IssuesOrder mCurrentOrder;
    ArrayList<Uri> mFilesToUpload;
    ActionBar.OnNavigationListener mNavigationCallbacks = new ActionBar.OnNavigationListener() {
        int lastPosition = -1;

        @Override
        public boolean onNavigationItemSelected(final int itemPosition, final long itemId) {
            L.d("pos=" + itemPosition);
            if (lastPosition >= 0) {
                if (!mSpinnerAdapter.isSeparator(itemPosition)) {
                    IssuesListFragment list = getMainFragment();
                    IssuesListFilter newFilter = mSpinnerAdapter.getFilter(itemPosition);
                    if (list != null) {
                        list.updateFilter(newFilter);
                    } else {
                        Bundle args = getMainFragmentArgs(null);
                        if (newFilter != null) {
                            newFilter.saveTo(args);
                        }
                        showMainFragment(args);
                    }
                }
            }
            lastPosition = itemPosition;
            return true;
        }
    };
    IssuesMainFilterAdapter mSpinnerAdapter;
    private List<FileUpload> mUploadedFiles;
    private ArrayList<String> mUploadErrors;
    private Server mUploadServer;
    private long mUploadToIssueId;
    private List<Attachment> mAttachments;

    @Override
    protected IssuesListFragment createMainFragment(Bundle args) {
        return IssuesListFragment.newInstance(args);
    }

    @Override
    protected IssueFragment createContentFragment(Bundle args) {
        return IssueFragment.newInstance(args);
    }

    @Override
    protected Fragment createEmptyFragment(Bundle args) {
        return EmptyFragment.newInstance(R.drawable.issues_empty_fragment);
    }

    @Override
    public Bundle getMainFragmentArgs(Bundle savedInstanceState) {
        final Intent intent = getIntent();

        final Bundle args = new Bundle(), extras;
        if ((extras = intent.getExtras()) != null) {
            args.putAll(extras);
        }

        mNavMode = ActionBar.NAVIGATION_MODE_LIST;

        // Get the intent, verify the action and get the query
        if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            final String query = intent.getStringExtra(SearchManager.QUERY);
            new IssuesListFilter(query).saveTo(args);
            mNavMode = ActionBar.NAVIGATION_MODE_STANDARD;
        }

        // Get issue sort order
        if (!args.keySet().contains(IssuesOrder.KEY_HAS_COLUMNS_ORDER)) {
            if (mCurrentOrder == null) {
                if (savedInstanceState == null) {
                    mCurrentOrder = IssuesOrder.fromPreferences(this);
                } else {
                    mCurrentOrder = IssuesOrder.fromBundle(savedInstanceState);
                }
            }

            if (mCurrentOrder != null) {
                mCurrentOrder.saveTo(args);
            }
        }

        saveMainFragmentState(args);

        return args;
    }

    @Override
    protected void onPreCreate() {
        supportRequestWindowFeature(Window.FEATURE_PROGRESS);
        supportRequestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setSupportProgressBarIndeterminate(true);
        setSupportProgressBarIndeterminateVisibility(false);

        AsyncTaskFragment.attachAsyncTaskFragment(this);

        Bundle args = getIntent().getExtras();
        if (args != null && args.containsKey(Constants.KEY_ISSUE_ID)) {
            selectContent(args);
        }

        // Get intent, action and MIME type
        Intent intent = getIntent();
        String action = intent.getAction();
        String type = intent.getType();

        // Handle share intent
        if (type != null && (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action))) {
            mFilesToUpload = new ArrayList<Uri>();
            if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
                ArrayList<Uri> uris = intent.<Uri>getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                for (Uri uri : uris) {
                    if (uri != null) {
                        mFilesToUpload.addAll(uris);
                    }
                }
            } else {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) {
                    mFilesToUpload.add(uri);
                }
            }
            L.d("Received share intent (" + action + ") with the following mime type: " + type + ", items:");
            for (Uri u : mFilesToUpload) {
                L.i(">: " + u);
            }
            L.i("------- end share intent");
            showServerPickerDialog();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        EasyTracker.getInstance(this).activityStart(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EasyTracker.getInstance(this).activityStop(this);
    }

    void saveNewColumnsOrder(final IssuesOrder orderColumns) {
        mCurrentOrder = orderColumns;
        if (mCurrentOrder != null) {
            mCurrentOrder.saveToPreferences(this);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mNavMode == ActionBar.NAVIGATION_MODE_LIST && getSupportActionBar().getNavigationMode() != mNavMode) {
            AsyncTaskFragment.runTask(this, ACTION_GET_NAVIGATION_SPINNER_DATA, null);
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        L.d("");
        supportInvalidateOptionsMenu();
    }

    @Override
    protected void onRestoreInstanceState(@android.support.annotation.NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        Gson gson = new Gson();
        mFilesToUpload = savedInstanceState.getParcelableArrayList(EXTRA_FILES_URI);
        mUploadErrors = savedInstanceState.getStringArrayList(EXTRA_FILES_CROUTONS);
        mUploadedFiles = gson.fromJson(savedInstanceState.getString(FileUpload.EXTRA_FILE_UPLOAD), FILE_UPLOAD_LIST_TYPE);
        mAttachments = gson.fromJson(savedInstanceState.getString(IssueFragment.KEY_ATTACHMENTS_JSON), Attachment.LIST_OF_ATTACHMENTS_TYPE);
        mUploadToIssueId = savedInstanceState.getLong(Constants.KEY_ISSUE_ID);
        mCurrentOrder = IssuesOrder.fromBundle(savedInstanceState);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        Gson gson = new Gson();
        outState.putParcelableArrayList(EXTRA_FILES_URI, mFilesToUpload);
        outState.putStringArrayList(EXTRA_FILES_CROUTONS, mUploadErrors);
        outState.putString(FileUpload.EXTRA_FILE_UPLOAD, gson.toJson(mUploadedFiles, FILE_UPLOAD_LIST_TYPE));
        outState.putString(IssueFragment.KEY_ATTACHMENTS_JSON, gson.toJson(mAttachments, Attachment.LIST_OF_ATTACHMENTS_TYPE));
        outState.putLong(Constants.KEY_ISSUE_ID, mUploadToIssueId);
        if (mCurrentOrder != null) {
            mCurrentOrder.saveTo(outState);
        }
    }

    /**
     * Used when creating a new issue: need to select the target server/project
     */
    private void showServerProjectPickerDialog() {
        DialogFragment newFragment = ServerProjectPickerFragment.newInstance(ServerProjectPickerDialog.DesiredSelection.SERVER_PROJECT);
        newFragment.show(getSupportFragmentManager(), "serverProjectPicker");
    }

    /**
     * Used when uploading an issue attachment: need to select the target server
     */
    private void showServerPickerDialog() {
        DialogFragment newFragment = ServerProjectPickerFragment.newInstance(ServerProjectPickerDialog.DesiredSelection.SERVER);
        newFragment.show(getSupportFragmentManager(), "serverPicker");
    }

    @Override
    public void onServerProjectPicked(final ServerProjectPickerDialog.DesiredSelection desiredSelection, final Server server, final Project project) {
        switch (desiredSelection) {
            case SERVER:
                mUploadServer = server;
                if (mFilesToUpload != null) {
                    Bundle args = new Bundle();
                    args.putParcelableArrayList(EXTRA_FILES_URI, mFilesToUpload);
                    AsyncTaskFragment.runTask(IssuesActivity.this, ACTION_UPLOAD_FILES, args);
                } else {
                    startActivityForResult(new Intent(this, FileChooserActivity.class), REQUEST_FILE_CHOOSER);
                }
                break;

            case SERVER_PROJECT:
                if (server != null && server.rowId > 0 && project != null && project.id > 0) {
                    Intent intent = new Intent(this, EditIssueActivity.class);
                    intent.putExtra(Constants.KEY_SERVER, server);
                    intent.putExtra(Constants.KEY_PROJECT, project);
                    startActivityForResult(intent, IssueUploader.CREATE_ISSUE);
                }
                break;
        }
    }

    /**
     * Used when attachment was uploaded: need to link it to an issue
     */
    private void showIssuePickerDialog(boolean isMultipleFileUpload) {
        DialogFragment newFragment = IssuePickerFragment.newInstance(isMultipleFileUpload);
        newFragment.show(getSupportFragmentManager(), "issuePicker");
    }

    @Override
    public void onIssuePicked(long issueId) {
        Bundle args = new Bundle();
        args.putLong(Constants.KEY_ISSUE_ID, issueId);
        args.putString(FileUpload.EXTRA_FILE_UPLOAD, new Gson().toJson(mUploadedFiles, FILE_UPLOAD_LIST_TYPE));
        args.putParcelable(Constants.KEY_SERVER, mUploadServer);
        // SERVER_ID is required because at the end we will call #selectContent with this bundle, and IssueFragment requires it
        args.putLong(Constants.KEY_SERVER_ID, mUploadServer.rowId);
        AsyncTaskFragment.runTask(this, ACTION_LINK_UPLOADED_FILES_TO_ISSUE, args);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        L.d("requestCode=" + requestCode + ", resultCode=" + resultCode + ", data=" + data);
        switch (requestCode) {
            case REQUEST_FILE_CHOOSER:
                if (resultCode == RESULT_OK) {
                    Uri selectedFile = data.getData();
                    try {
                        String path = FileUtils.getPath(this, selectedFile);
                        if (path != null && FileUtils.isLocal(path)) {
                            Bundle args = new Bundle();
                            args.putLong(Constants.KEY_ISSUE_ID, mUploadToIssueId);
                            args.putString(EXTRA_FILE_PATH, path);
                            AsyncTaskFragment.runTask(this, ACTION_UPLOAD_FILE, args);
                        } else {
                            L.e("Invalid selected file: " + selectedFile + ", path: " + path, null);
                            Crouton.makeText(this, getString(R.string.issue_attn_select_file_invalid_path), Style.ALERT, getCroutonHolder()).show();
                        }
                    } catch (URISyntaxException e) {
                        L.e("Couldn't get file path", e);
                        Crouton.makeText(this, getString(R.string.issue_attn_select_file_failed, e.getLocalizedMessage()), Style.ALERT, getCroutonHolder()).show();
                    }
                }
                break;

            case IssueUploader.CREATE_ISSUE:
            case IssueUploader.EDIT_ISSUE:
                if (resultCode == RESULT_OK) {
                    final Bundle extras = data == null || data.getExtras() == null ? new Bundle() : data.getExtras();
                    extras.putInt(IssueUploader.ISSUE_ACTION, requestCode);
                    AsyncTaskFragment.runTask(this, ACTION_UPLOAD_ISSUE, extras);
                } else {
                    EditIssueActivity.handleRevertCrouton(this, getCroutonHolder(), requestCode, data.getExtras());
                }
        }
    }

    /**
     * Get the correct view ID that will contain any {@link de.keyboardsurfer.android.widget.crouton.Crouton} to be displayed in this context
     *
     * @return The view ID to pass to the fourth arg of {@link Crouton#makeText(android.app.Activity, int, de.keyboardsurfer.android.widget.crouton.Style, int)}
     */
    private int getCroutonHolder() {
        return isSplitScreen() ? R.id.sa__right_pane : R.id.sa__left_pane;
    }

    @Override
    public void selectContent(Bundle args) {
        super.selectContent(args);
        // Prevent crouton from showing if we display another view (likely to hide the "modifications discarded" crouton back from
        // EditIssueActivity)
        Crouton.cancelAllCroutons();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        Fragment content = getContentFragment();
        switch (item.getItemId()) {
            case android.R.id.home:
                if (isSplitScreen()) {
                    NavUtils.navigateUpFromSameTask(this);
                } else if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                    getSupportFragmentManager().popBackStack();
                } else {
                    NavUtils.navigateUpFromSameTask(this);
                }
                return true;

            case R.id.menu_issues_search:
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB) {
                    // For gingerbread compatibility, after it's with the action bar search view
                    onSearchRequested();
                }
                return true;

            case R.id.menu_issues_add:
                showServerProjectPickerDialog();
                return true;

            case R.id.menu_issue_edit:
                if (content != null) {
                    Issue issue = ((IssueFragment) content).getIssue();
                    if (issue != null) {
                        String json = new Gson().toJson(issue, Issue.class);
                        Intent intent = new Intent(this, EditIssueActivity.class);
                        intent.putExtra(IssueFragment.KEY_ISSUE_JSON, json);
                        startActivityForResult(intent, IssueUploader.EDIT_ISSUE);
                    }
                }
                return true;

            case R.id.menu_issue_upload_attachment:
                boolean needServerPicker = true;
                if (content != null) {
                    Issue issue = ((IssueFragment) content).getIssue();
                    if (issue != null && issue.server != null) {
                        mUploadServer = issue.server;
                        needServerPicker = false;
                        mUploadToIssueId = issue.id;
                        startActivityForResult(new Intent(this, FileChooserActivity.class), REQUEST_FILE_CHOOSER);
                    }
                }
                if (needServerPicker) {
                    showServerPickerDialog();
                }
                return true;

            case R.id.menu_issue_delete:
                if (content != null) {
                    final Issue issue = ((IssueFragment) content).getIssue();
                    if (issue != null && issue.server != null && issue.id > 0) {
                        new AlertDialog.Builder(this).setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(final DialogInterface dialog, final int which) {
                                AsyncTaskFragment.runTask(IssuesActivity.this, ACTION_DELETE_ISSUE, issue);
                                dialog.dismiss();
                            }
                        }).setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(final DialogInterface dialog, final int which) {
                                dialog.dismiss();
                            }
                        }).setMessage(getString(R.string.issue_delete_confirm_dialog)).show();
                    }
                }
                return true;

            case R.id.menu_issue_browser:
                if (content != null) {
                    Issue issue = ((IssueFragment) content).getIssue();
                    if (issue != null) {
                        String url = issue.server.serverUrl;
                        if (!url.endsWith("/")) {
                            url += "/";
                        }
                        url += "issues/" + issue.id;

                        final Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setData(Uri.parse(url));
                        startActivity(i);
                    }
                } else {
                    supportInvalidateOptionsMenu();
                }
                return true;

            case R.id.menu_issues_sort:
                final IssuesOrderingFragment issuesOrder = IssuesOrderingFragment.newInstance(mCurrentOrder);
                issuesOrder.setOrderSelectionListener(new IssuesOrderSelectionListener() {
                    @Override
                    public void onOrderColumnsSelected(final IssuesOrder orderColumns) {
                        saveNewColumnsOrder(orderColumns);

                        IssuesListFragment list = getMainFragment();
                        if (list != null) {
                            list.updateColumnsOrder(orderColumns);
                        } else {
                            Bundle args = getMainFragmentArgs(null);
                            if (orderColumns != null) {
                                orderColumns.saveTo(args);
                            }
                            showMainFragment(args);
                        }
                    }
                });
                issuesOrder.show(getSupportFragmentManager(), "issues_order");
                return true;

            case R.id.menu_issues_refresh:
                AsyncTaskFragment.runTask(this, ACTION_REFRESH_ISSUES, null);
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_issues, menu);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            final MenuItem item = menu.findItem(R.id.menu_issues_search);
            if (item != null) {
                final SearchView searchView = (SearchView) item.getActionView();
                if (searchView != null) {
                    final SearchManager searchManager = (SearchManager) getSystemService(SEARCH_SERVICE);
                    searchView.setSearchableInfo(searchManager.getSearchableInfo(getComponentName()));
                    searchView.setIconifiedByDefault(false);
                }
            }
        }

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public void onPreExecute(final int action, final Object parameters) {
        if (action == ACTION_UPLOAD_FILES) {
            Crouton.showText(this, getString(R.string.issue_attn_upload_in_progress), Style.INFO);
        }
        if (action != ACTION_REFRESH_ISSUES) {
            setSupportProgressBarIndeterminateVisibility(true);
        }
    }

    @Override
    public Object doInBackGround(Context applicationContext, final int action, final Object parameters) {
        switch (action) {
            case ACTION_REFRESH_ISSUES:
                IssuesSyncAdapterService.Synchronizer synchronizer = new IssuesSyncAdapterService.Synchronizer(applicationContext);
                ServersDbAdapter db = new ServersDbAdapter(applicationContext);
                db.open();
                for (Server server : db.selectAll()) {
                    synchronizer.synchronizeIssues(server, null, 0);
                }
                db.close();
                break;

            case ACTION_ISSUE_LOAD_ISSUE:
                return IssueOverviewFragment.loadIssue(applicationContext, (Bundle) parameters);

            case ACTION_ISSUE_LOAD_OVERVIEW:
                return IssueOverviewFragment.loadIssueOverview(applicationContext, (Issue) parameters);

            case ACTION_ISSUE_LOAD_ATTACHMENTS:
                return IssueOverviewFragment.loadIssueAttachments(applicationContext, (Issue) parameters, this);

            case ACTION_DELETE_ISSUE:
                return IssueUploader.deleteIssue(this, (Issue) parameters);

            case ACTION_UPLOAD_ISSUE:
                return IssueUploader.uploadIssue(applicationContext, (Bundle) parameters);

            case ACTION_ISSUE_TOGGLE_FAVORITE:
                IssuesDbAdapter idb = new IssuesDbAdapter(applicationContext);
                idb.open();
                ServersDbAdapter sdb = new ServersDbAdapter(idb);
                Bundle args = (Bundle) parameters;
                final long serverId = args.getLong(Constants.KEY_SERVER_ID);
                final long issueId = args.getLong(Constants.KEY_ISSUE_ID);
                Issue issue = idb.select(sdb.getServer(serverId), issueId, null);
                issue.is_favorite = args.getBoolean(IssuesDbAdapter.KEY_IS_FAVORITE);
                idb.update(issue);
                idb.close();
                break;

            case ACTION_GET_NAVIGATION_SPINNER_DATA:
                final QueriesDbAdapter qdb = new QueriesDbAdapter(applicationContext);
                qdb.open();
                final List<Query> queries = qdb.selectAll(null);
                final ProjectsDbAdapter pdb = new ProjectsDbAdapter(qdb);
                final List<Project> projects = pdb.selectAll(null, ProjectsDbAdapter.KEY_IS_SYNC_BLOCKED + " != 1");
                pdb.close();

                return new IssuesMainFilterAdapter(applicationContext, queries, projects);

            case ACTION_UPLOAD_FILE:
                String path = ((Bundle) parameters).getString(EXTRA_FILE_PATH);
                File file = new File(path);
                Object fileUpload = new FileUploader().uploadFile(this, mUploadServer, file);
                L.d("Uploaded " + path + ", result: " + fileUpload);
                // Enrich object if we can
                if (fileUpload instanceof FileUpload && path != null) {
                    ((FileUpload) fileUpload).filename = path.substring(path.lastIndexOf("/") + 1);
                }
                return fileUpload;

            case ACTION_UPLOAD_FILES:
                List<Uri> files = ((Bundle) parameters).getParcelableArrayList(EXTRA_FILES_URI);
                if (files != null && files.size() > 0) {
                    ContentResolver cr = getContentResolver();
                    List<FileUpload> uploads = new ArrayList<FileUpload>();
                    InputStream is;
                    String p;
                    for (Uri fileToUpload : files) {
                        try {
                            if (fileToUpload != null) {
                                is = cr.openInputStream(fileToUpload);
                                if (is != null) {
                                    Object r = new FileUploader().uploadFile(this, mUploadServer, is);
                                    L.d("Uploaded " + fileToUpload + ", result: " + r);
                                    if (r instanceof FileUpload) {
                                        // Enrich object if we can
                                        if ("media".equals(fileToUpload.getHost())) {
                                            p = Util.getRealPathFromURI(applicationContext, fileToUpload);
                                        } else {
                                            p = fileToUpload.getPath();
                                        }
                                        if (!TextUtils.isEmpty(p)) {
                                            ((FileUpload) r).filename = p.substring(p.lastIndexOf("/") + 1);
                                        }

                                        uploads.add((FileUpload) r);
                                    }
                                } else {
                                    if (mUploadErrors == null) {
                                        mUploadErrors = new ArrayList<String>();
                                    }
                                    mUploadErrors.add(getString(R.string.err_upload_no_support_for_content));
                                    L.e("Couldn't upload " + fileToUpload, null);
                                }
                            }
                        } catch (FileNotFoundException e) {
                            if (mUploadErrors == null) {
                                mUploadErrors = new ArrayList<String>();
                            }
                            mUploadErrors.add(getString(R.string.err_upload_file_not_found));
                            L.e("Couldn't upload " + fileToUpload, e);
                        }
                    }
                    return uploads;
                }
                return null;

            case ACTION_LINK_UPLOADED_FILES_TO_ISSUE:
                // Retrieve Issue object
                Bundle params = (Bundle) parameters;
                Server server = params.getParcelable(Constants.KEY_SERVER);
                IssuesDbAdapter uidb = new IssuesDbAdapter(applicationContext);
                uidb.open();
                Issue issueUpload = uidb.select(server, params.getLong(Constants.KEY_ISSUE_ID), null);
                uidb.close();

                // Link the attachment(s) with the issue
                issueUpload.uploads = new Gson().fromJson(params.getString(FileUpload.EXTRA_FILE_UPLOAD), FILE_UPLOAD_LIST_TYPE);
                Bundle uploadArgs = new Bundle();
                uploadArgs.putString(IssueFragment.KEY_ISSUE_JSON, new Gson().toJson(issueUpload, Issue.class));
                Object result = IssueUploader.uploadIssue(applicationContext, uploadArgs);
                L.d("link files/issue: " + result);
                return result;
        }

        return null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Crouton.cancelAllCroutons();
    }

    @Override
    public void onPostExecute(final int action, final Object parameters, final Object result) {
        if (action != ACTION_REFRESH_ISSUES) {
            setSupportProgressBarIndeterminateVisibility(false);
        }

        Fragment frag;
        IssueFragment content = getContentFragment();

        switch (action) {
            case ACTION_REFRESH_ISSUES:
                if (getMainFragment() != null) {
                    getMainFragment().refreshList();
                }
                break;

            case ACTION_ISSUE_LOAD_ATTACHMENTS:
                if (content != null) {
                    frag = content.getFragmentFromViewPager(IssueFragment.FRAGMENT_OVERVIEW);
                    if (frag != null && frag instanceof IssueAttachmentsFragment) { // if frag is not null, it's likely a pull to refresh on the attachments tab
                        ((IssueAttachmentsFragment) frag).onAttachmentsLoaded();
                    }
                }
                // don't break, the following must be executed also for ACTION_ISSUE_LOAD_ATTACHMENTS
            case ACTION_ISSUE_LOAD_ISSUE:
            case ACTION_ISSUE_LOAD_OVERVIEW:
                if (content != null) {
                    frag = content.getFragmentFromViewPager(IssueFragment.FRAGMENT_OVERVIEW);
                    if (frag != null && frag instanceof IssueOverviewFragment) {
                        final IssueOverviewFragment issueOverviewFragment = (IssueOverviewFragment) frag;
                        switch (action) {
                            case ACTION_ISSUE_LOAD_ISSUE:
                                issueOverviewFragment.onIssueLoaded((Issue) result);
                                break;

                            case ACTION_ISSUE_LOAD_ATTACHMENTS:
                                if (result instanceof JsonNetworkError) {
                                    issueOverviewFragment.onNetworkError((JsonNetworkError) result);
                                } else {
                                    issueOverviewFragment.onIssueOverviewLoaded((String) result);
                                }
                                break;

                            case ACTION_ISSUE_LOAD_OVERVIEW:
                                issueOverviewFragment.onIssueOverviewLoaded((String) result);
                                break;
                        }
                    }
                }
                break;

            case ACTION_DELETE_ISSUE:
                IssueUploader.handleDelete(this, (Issue) parameters, result);
                if (isSplitScreen()) {
                    selectEmptyFragment(new Bundle());
                    if (getMainFragment() != null) {
                        getMainFragment().refreshList();
                    }
                } else if (getActiveContent() == ActiveContent.CONTENT) {
                    showMainFragment(getMainFragmentArgs(null));
                } else if (getMainFragment() != null) {
                    getMainFragment().refreshList();
                }
                break;

            case ACTION_LINK_UPLOADED_FILES_TO_ISSUE:
                // When linking an issue with an attachment, the server doesn't send back the full issue, but an empty string
                if (result instanceof String && TextUtils.isEmpty((String) result)) {
                    selectContent((Bundle) parameters);
                } else if (result instanceof JsonNetworkError) {
                    ((JsonNetworkError) result).displayCrouton(this, getCroutonHolder());
                } else {
                    L.e("Unexpected app state", null);
                }

                // Anyway, free some ram
                mUploadedFiles = null;
                break;

            case ACTION_UPLOAD_ISSUE:
                IssueUploader.handleAddEdit(this, (Bundle) parameters, result);

                break;

            case ACTION_ISSUE_TOGGLE_FAVORITE:
                if (isSplitScreen()) {
                    getMainFragment().refreshList();
                }
                break;

            case ACTION_GET_NAVIGATION_SPINNER_DATA:
                mSpinnerAdapter = (IssuesMainFilterAdapter) result;
                final ActionBar ab = getSupportActionBar();
                ab.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
                ab.setListNavigationCallbacks(mSpinnerAdapter, mNavigationCallbacks);
                L.d("setlistnavigationcallbacks: " + mSpinnerAdapter + ", " + mNavigationCallbacks);
                break;

            case ACTION_UPLOAD_FILE:
                if (result instanceof JsonNetworkError) {
                    ((JsonNetworkError) result).displayCrouton(this, getCroutonHolder());
                } else {
                    L.i("Congratulations, it's a file! " + result);
                    mUploadedFiles = new ArrayList<FileUpload>();
                    mUploadedFiles.add((FileUpload) result);
                    Bundle params = (Bundle) parameters;
                    if (params.containsKey(Constants.KEY_ISSUE_ID) && params.getLong(Constants.KEY_ISSUE_ID, 0) > 0) {
                        onIssuePicked(params.getLong(Constants.KEY_ISSUE_ID));
                    } else {
                        showIssuePickerDialog(false);
                    }
                }
                break;

            case ACTION_UPLOAD_FILES:
                List uploads = (List) result;
                mUploadedFiles = new ArrayList<FileUpload>();
                for (Object upload : uploads) {
                    if (upload instanceof JsonNetworkError) {
                        ((JsonNetworkError) upload).displayCrouton(this, getCroutonHolder());
                    } else {
                        L.i("Successfully uploaded file " + upload);
                        mUploadedFiles.add((FileUpload) upload);
                    }
                }

                // If none worked, don't bother to continue
                if (mUploadedFiles.size() == 0) {
                    if (mUploadErrors != null && mUploadErrors.size() > 0) {
                        for (String error : mUploadErrors) {
                            Crouton.showText(this, error, Style.ALERT);
                        }
                    } else {
                        L.e("Failed with no error, WTF?", null);
                    }
                } else {
                    Bundle params = (Bundle) parameters;
                    if (params.containsKey(Constants.KEY_ISSUE_ID) && params.getLong(Constants.KEY_ISSUE_ID, 0) > 0) {
                        onIssuePicked(params.getLong(Constants.KEY_ISSUE_ID));
                    } else {
                        showIssuePickerDialog(true);
                    }
                }
                break;
        }
    }

    @Override
    public void onAttachmentsLoaded(List<Attachment> attachments) {
        mAttachments = attachments;
    }

    public List<Attachment> getAttachments() {
        return mAttachments;
    }
}
