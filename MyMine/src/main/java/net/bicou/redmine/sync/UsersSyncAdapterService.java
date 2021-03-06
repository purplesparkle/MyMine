package net.bicou.redmine.sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Service;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;

import net.bicou.redmine.app.ssl.SupportSSLKeyManager;
import net.bicou.redmine.data.Server;
import net.bicou.redmine.data.json.Project;
import net.bicou.redmine.data.json.UsersList;
import net.bicou.redmine.data.sqlite.ProjectsDbAdapter;
import net.bicou.redmine.data.sqlite.ServersDbAdapter;
import net.bicou.redmine.platform.UsersManager;
import net.bicou.redmine.util.L;

import java.util.List;

/**
 * Service to handle Account sync. This is invoked with an intent with action ACTION_AUTHENTICATOR_INTENT. It instantiates the syncadapter and returns its IBinder.
 */
public class UsersSyncAdapterService extends Service {
	public static final String SYNC_MARKER_KEY = "net.bicou.redmine.sync.Users.marker";
	private static final Object sSyncAdapterLock = new Object();

	private static SyncAdapter sSyncAdapter = null;

	@Override
	public void onCreate() {
		L.d("");
		synchronized (sSyncAdapterLock) {
			if (sSyncAdapter == null) {
				sSyncAdapter = new SyncAdapter(getApplicationContext(), true);
			}
		}
	}

	@Override
	public IBinder onBind(final Intent intent) {
		L.d("");
		return sSyncAdapter.getSyncAdapterBinder();
	}

	/**
	 * SyncAdapter implementation for syncing sample SyncAdapter contacts to the platform ContactOperations provider. This sample shows a basic 2-way sync between
	 * the
	 * client and a sample server. It also contains an example of how to update the contacts' status messages, which would be useful for a messaging or social
	 * networking client.
	 */
	private static class SyncAdapter extends AbstractThreadedSyncAdapter {
		private static final boolean NOTIFY_AUTH_FAILURE = true;

		private final AccountManager mAccountManager;

		private final Context mContext;

		public SyncAdapter(final Context context, final boolean autoInitialize) {
			super(context, autoInitialize);
			L.d("context=" + context + " autoinit=" + autoInitialize);
			mContext = context;
			mAccountManager = AccountManager.get(context);
		}

		@Override
		public void onPerformSync(final Account account, final Bundle extras, final String authority, final ContentProviderClient provider,
		                          final SyncResult syncResult) {
			L.d("account=" + account + " extras=" + extras + " auth=" + authority + " prov=" + provider + " result=" + syncResult);

			final long lastSyncMarker = getServerSyncMarker(account);

			final ServersDbAdapter db = new ServersDbAdapter(mContext);
			db.open();
			Server server = db.getServer(account.name);

			if (server == null) {
				L.e("Couldn't get the server for account: " + account, null);
				return;
			}

			// Init SSL and certificates
			SupportSSLKeyManager.init(mContext);

			// Sync all users pages of all projects
			long newSyncState = 0, tmp;
			UsersList users = NetworkUtilities.syncUsers(mContext, server);
			if (users != null && users.users != null && users.users.size() > 0) {
				tmp = UsersManager.updateUsers(mContext, server, users.users, lastSyncMarker);
				if (tmp > newSyncState) {
					newSyncState = tmp;
				}
			} else {
				// try to get the users from the "new issue" page
				ProjectsDbAdapter pdb = new ProjectsDbAdapter(db);
				List<Project> projects = pdb.selectAll();

				for (Project project : projects) {
					users = NetworkUtilities.syncUsersHack(mContext, server);
					if (users != null && users.users != null && users.users.size() > 0) {
						tmp = UsersManager.updateUsers(mContext, server, users.users, lastSyncMarker);
						if (tmp > newSyncState) {
							newSyncState = tmp;
						}
					}
				}
			}
			db.close();

			if (newSyncState > 0) {
				setServerSyncMarker(account, newSyncState);
			}
		}

		/**
		 * This helper function fetches the last known high-water-mark we received from the server - or 0 if we've never synced.
		 *
		 * @param account the account we're syncing
		 * @return the change high-water-mark
		 */
		private long getServerSyncMarker(final Account account) {
			final String markerString = mAccountManager.getUserData(account, SYNC_MARKER_KEY);
			if (!TextUtils.isEmpty(markerString)) {
				return Long.parseLong(markerString);
			}
			return 0;
		}

		/**
		 * Save off the high-water-mark we receive back from the server.
		 *
		 * @param account The account we're syncing
		 * @param marker  The high-water-mark we want to save.
		 */
		private void setServerSyncMarker(final Account account, final long marker) {
			mAccountManager.setUserData(account, SYNC_MARKER_KEY, Long.toString(marker));
		}
	}
}
