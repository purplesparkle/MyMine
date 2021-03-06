package net.bicou.redmine.app.misc;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.Toast;
import net.bicou.redmine.R;
import net.bicou.redmine.app.ga.TrackedFragment;
import net.bicou.redmine.sync.SyncUtils;
import net.bicou.redmine.util.L;

public class WaitForSyncFragment extends TrackedFragment {
	public static WaitForSyncFragment newInstance(final Bundle args) {
		final WaitForSyncFragment frag = new WaitForSyncFragment();
		frag.setArguments(args);
		return frag;
	}

	@Override
	public View onCreateView(final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
		L.d("");
		final View fragmentLayout = inflater.inflate(R.layout.frag_waiting_sync, container, false);
		fragmentLayout.findViewById(R.id.loading_check_sync_button).setOnClickListener(new OnClickListener() {
			@Override
			public void onClick(final View v) {
				try {
					final Intent intent = new Intent(Settings.ACTION_SYNC_SETTINGS);
					intent.putExtra(Settings.EXTRA_AUTHORITIES, SyncUtils.SYNC_AUTHORITIES);
					startActivity(intent);
				} catch (final Exception e) {
					L.e("Couldn't get to settings because of a " + e.toString());
					Toast.makeText(getActivity(), R.string.setup_sync_help, Toast.LENGTH_LONG).show();
				}
			}
		});
		return fragmentLayout;
	}
}
