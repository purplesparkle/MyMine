package net.bicou.redmine.app.roadmap;

import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.widget.ArrayAdapter;
import com.actionbarsherlock.app.ActionBar;
import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.actionbarsherlock.view.MenuItem;
import com.google.gson.Gson;
import net.bicou.redmine.Constants;
import net.bicou.redmine.R;
import net.bicou.redmine.app.ProjectsSpinnerAdapter;
import net.bicou.redmine.app.RefreshProjectsTask;
import net.bicou.redmine.app.issues.IssuesOrderColumnsAdapter;
import net.bicou.redmine.app.issues.IssuesOrderingFragment;
import net.bicou.redmine.data.json.Project;
import net.bicou.redmine.data.json.Version;
import net.bicou.redmine.util.L;

import java.util.ArrayList;
import java.util.List;

public class RoadmapActivity extends SherlockFragmentActivity implements RoadmapsListFragment.RoadmapSelectionListener, ActionBar.OnNavigationListener,
		RoadmapsListFragment.CurrentProjectInfo {
	@Override
	public void onRoadmapSelected(Version version) {
		L.d("");
		int fragId = android.R.id.content;//TODO mIsSplitScreen ? R.id.roadmaps_pane_roadmap : R.id.roadmaps_pane_list;
		Bundle args = new Bundle();
		args.putString(RoadmapFragment.KEY_VERSION_JSON, new Gson().toJson(version));
		getSupportFragmentManager().beginTransaction().replace(fragId, RoadmapFragment.newInstance(args)).addToBackStack("prout").commit();
	}

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		initProjectsSpinner(savedInstanceState);

		//TODO mIsSplitScreen = findViewById(R.id.roadmaps_pane_roadmap) != null;
		final Bundle args = new Bundle();

		// Setup fragments
		if (savedInstanceState == null) {
			// Setup list view
			getSupportFragmentManager().beginTransaction().replace(android.R.id.content, RoadmapsListFragment.newInstance(args)).commit();
		}

		// Screen rotation on 7" tablets
		//		if (savedInstanceState != null && mIsSplitScreen != savedInstanceState.getBoolean(KEY_IS_SPLIT_SCREEN)) {
		//			final Fragment f = getSupportFragmentManager().findFragmentById(R.id.roadmaps_pane_list);
		//			if (f != null && f instanceof RoadmapsListFragment) {
		//				// TODO ((RoadmapsListFragment) f).updateSplitScreenState(mIsSplitScreen);
		//			}
		//		}
	}

	@Override
	public boolean onOptionsItemSelected(final MenuItem item) {
		final int id = android.R.id.content; //TODO mIsSplitScreen ? android.R.id.content : android.R.id.content;
		final Fragment frag = getSupportFragmentManager().findFragmentById(id);
		L.d("");
		switch (item.getItemId()) {
		case R.id.menu_roadmap_sort_issues:
			if (frag instanceof RoadmapFragment) {
				final RoadmapFragment rf = (RoadmapFragment) frag;
				final IssuesOrderingFragment issuesOrder = IssuesOrderingFragment.newInstance(rf.getCurrentOrder());
				issuesOrder.setOrderSelectionListener(new IssuesOrderingFragment.IssuesOrderSelectionListener() {
					@Override
					public void onOrderColumnsSelected(final ArrayList<IssuesOrderColumnsAdapter.OrderColumn> orderColumns) {
						rf.setNewIssuesOrder(orderColumns);

						final FragmentManager fm = getSupportFragmentManager();
						final Fragment frag = fm.findFragmentById(android.R.id.content);
					}
				});
				issuesOrder.show(getSupportFragmentManager(), "issues_order");
			}
			return true;

		default:
			return super.onOptionsItemSelected(item);
		}
	}


	@Override
	public void onResume() {
		super.onResume();
		//		final Fragment frag = getSupportFragmentManager().findFragmentById(android.R.id.content);
		//		L.d("");
		//		final Bundle args = new Bundle();
		//		if (frag instanceof RoadmapsListFragment) {
		//			//			((RoadmapsListFragment) frag).updateCurrentRoadmap(mRoadmaps.get(mCurrentRoadmapPosition).id);
		//			//			if (mIsSplitScreen) {
		//			//				final Fragment f = getSupportFragmentManager().findFragmentById(R.id.roadmaps_pane_roadmap);
		//			//				if (f != null) {
		//			//					getSupportFragmentManager().beginTransaction().remove(f).commit();
		//			//				}
		//			//			}
		//			getSupportFragmentManager().beginTransaction().replace(android.R.id.content, RoadmapFragment.newInstance(args)).commit();
		//		} else if (frag instanceof RoadmapFragment) {
		//			getSupportFragmentManager().beginTransaction().replace(android.R.id.content, RoadmapsListFragment.newInstance(args)).commit();
		//		}
		//		supportInvalidateOptionsMenu();
		//
		//
		//		mProjects.clear();
		refreshProjectsList();
	}

	@Override
	protected void onSaveInstanceState(final Bundle outState) {
		super.onSaveInstanceState(outState);
		L.d("");
		outState.putParcelableArrayList(KEY_REDMINE_PROJECTS_LIST, mProjects);
		outState.putInt(Constants.KEY_PROJECT_POSITION, mCurrentProjectPosition);
	}

	//-----------------------------------------------------------
	// Projects spinner

	public static final String KEY_REDMINE_PROJECTS_LIST = "net.bicou.mymine.RedmineProjectsList";

	protected ArrayList<Project> mProjects;
	protected ArrayAdapter<Project> mAdapter;
	public int mCurrentProjectPosition;

	private void initProjectsSpinner(Bundle savedInstanceState) {
		mCurrentProjectPosition = -1;
		if (savedInstanceState == null) {
			mProjects = new ArrayList<Project>();
			mAdapter = new ProjectsSpinnerAdapter(this, R.layout.main_nav_item, mProjects);
			mCurrentProjectPosition = -1;
		}

		// Specific project/server?
		final Bundle args = getIntent().getExtras();
		if (args != null && args.containsKey(Constants.KEY_PROJECT_POSITION)) {
			mCurrentProjectPosition = args.getInt(Constants.KEY_PROJECT_POSITION);
		}
	}

	@Override
	protected void onRestoreInstanceState(final Bundle savedInstanceState) {
		super.onRestoreInstanceState(savedInstanceState);

		mProjects = savedInstanceState.getParcelableArrayList(KEY_REDMINE_PROJECTS_LIST);
		mAdapter = new ProjectsSpinnerAdapter(this, R.layout.main_nav_item, mProjects);
		mCurrentProjectPosition = savedInstanceState.getInt(Constants.KEY_PROJECT_POSITION);

		enableListNavigationMode();
	}

	private void enableListNavigationMode() {
		L.d("current proj pos=" + mCurrentProjectPosition);
		final ActionBar ab = getSupportActionBar();
		ab.setNavigationMode(ActionBar.NAVIGATION_MODE_LIST);
		ab.setListNavigationCallbacks(mAdapter, this);
		if (mCurrentProjectPosition >= 0) {
			ab.setSelectedNavigationItem(mCurrentProjectPosition);
		}
	}

	public void refreshProjectsList() {
		if (mProjects.size() > 0) {
			return;
		}

		new RefreshProjectsTask(RoadmapActivity.this, new RefreshProjectsTask.ProjectsLoadCallbacks() {
			@Override
			public void onProjectsLoaded(List<Project> projectList) {
				mProjects.addAll(projectList);
				if (getSupportActionBar().getNavigationMode() != ActionBar.NAVIGATION_MODE_LIST) {
					enableListNavigationMode();
				}
			}
		}).execute();
	}

	@Override
	public boolean onNavigationItemSelected(final int itemPosition, final long itemId) {
		L.d("position: " + itemPosition);
		if (mProjects == null || itemPosition < 0 || itemPosition > mProjects.size()) {
			return true;
		}

		mCurrentProjectPosition = itemPosition;

		FragmentManager fm = getSupportFragmentManager();
		Fragment f = fm.findFragmentById(android.R.id.content);
		if (f != null && f instanceof RoadmapsListFragment) {
			((RoadmapsListFragment) f).updateRoadmapsList();
		} else {
			fm.beginTransaction().replace(android.R.id.content, RoadmapsListFragment.newInstance(new Bundle())).commit();
		}

		return true;
	}

	@Override
	public Project getCurrentProject() {
		if (mProjects == null || mCurrentProjectPosition < 0 || mCurrentProjectPosition >= mProjects.size()) {
			return null;
		}
		return mProjects.get(mCurrentProjectPosition);
	}
}