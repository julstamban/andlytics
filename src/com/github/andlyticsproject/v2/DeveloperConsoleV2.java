package com.github.andlyticsproject.v2;

import java.io.IOException;
import java.net.ProtocolException;
import java.util.List;

import org.apache.http.client.CookieStore;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;

import android.content.Context;
import android.util.Log;

import com.github.andlyticsproject.R;
import com.github.andlyticsproject.exception.AuthenticationException;
import com.github.andlyticsproject.exception.DeveloperConsoleException;
import com.github.andlyticsproject.exception.MultiAccountAcception;
import com.github.andlyticsproject.exception.NetworkException;
import com.github.andlyticsproject.model.AppInfo;
import com.github.andlyticsproject.model.AppStats;
import com.github.andlyticsproject.model.Comment;

/**
 * This is a WIP class representing the new v2 version of the developer console.
 * The aim is to build it from scratch to make it a light weight and as well
 * documented at the end as possible. Once it is done and available to all
 * users, we will rip out the old code and replace it with this.
 * 
 * Once v2 is available to all users, there is scope for better utilising the
 * available statistics data, so keep that in mind when developing this class.
 * For now though, keep it simple and get it working.
 * 
 * See https://github.com/AndlyticsProject/andlytics/wiki/Developer-Console-v2
 * for some more documentation
 * 
 * This class fetches the data, which is then passed using {@link JsonParser}
 * 
 */
public class DeveloperConsoleV2 {

	// 30 seconds -- for both socket and connection
	public static final int TIMEOUT = 30 * 1000;

	private static final String TAG = DeveloperConsoleV2.class.getSimpleName();

	private static final boolean DEBUG = false;

	// Base urls
	private static final String URL_DEVELOPER_CONSOLE = "https://play.google.com/apps/publish/v2/";
	private static final String URL_APPS = "https://play.google.com/apps/publish/v2/androidapps";
	private static final String URL_STATISTICS = "https://play.google.com/apps/publish/v2/statistics";
	private static final String URL_REVIEWS = "https://play.google.com/apps/publish/v2/reviews";

	// Payloads used in POST requests
	private static final String PAYLOAD_APPS = "{\"method\":\"fetch\","
			+ "\"params\":{\"2\":1,\"3\":7},\"xsrf\":\"%s\"}";
	// 1$: package name, 2$: XSRF
	private static final String PAYLOAD_RATINGS = "{\"method\":\"getRatings\","
			+ "\"params\":{\"1\":[\"%1$s\"]},\"xsrf\":\"%2$s\"}";
	// 1$: package name, 2$: start, 3$: end, 4$ XSRF
	private static final String PAYLOAD_COMMENTS = "{\"method\":\"getReviews\","
			+ "\"params\":{\"1\":\"%1$s\",\"2\":%2$d,\"3\":%3$d},\"xsrf\":\"%4$s\"}";
	// 1$: package name, 2$: stats type, 3$: stats by, 4$: XSRF
	private static final String PAYLOAD_STATISTICS = "{\"method\":\"getCombinedStats\","
			+ "\"params\":{\"1\":\"%1$s\",\"2\":1,\"3\":%2$d,\"4\":[%3$d]},\"xsrf\":\"%4$s\"}";

	// Represents the different ways to break down statistics by e.g. by android
	// version
	protected static final int STATS_BY_ANDROID_VERSION = 1;
	protected static final int STATS_BY_DEVICE = 2;
	protected static final int STATS_BY_COUNTRY = 3;
	protected static final int STATS_BY_LANGUAGE = 4;
	protected static final int STATS_BY_APP_VERSION = 5;
	protected static final int STATS_BY_CARRIER = 6;

	// Represents the different types of statistics e.g. active device installs
	protected static final int STATS_TYPE_ACTIVE_DEVICE_INSTALLS = 1;
	protected static final int STATS_TYPE_TOTAL_USER_INSTALLS = 8;

	private DefaultHttpClient httpClient;
	private AuthInfo authInfo;
	private DevConsoleAuthenticator authenticator;
	private String accountName;

	// TODO add factory method for token authenticator when available
	public static DeveloperConsoleV2 createForAccount(Context ctx, String accountName) {
		// this is pre-configured with needed headers and keeps track
		// of cookies, etc.
		DefaultHttpClient httpClient = HttpClientFactory.createDevConsoleHttpClient(TIMEOUT);
		// XXX put password in a private resources
		String password = ctx.getResources().getString(R.string.dev_console_password);
		DevConsoleAuthenticator authenticator = new PasswordAuthenticator(accountName, password,
				httpClient);

		return new DeveloperConsoleV2(httpClient, authenticator);
	}

	private DeveloperConsoleV2(DefaultHttpClient httpClient, DevConsoleAuthenticator authenticator) {
		this.httpClient = httpClient;
		this.authenticator = authenticator;
		this.accountName = authenticator.getAccountName();
	}

	// TODO Decide on which exceptions should actually be thrown and by which
	// methods, and what data we should include in them
	// => JSONException is too low level for this, wrap with
	// DeveloperConsoleException
	// or RE

	/**
	 * Gets a list of available apps for the given account
	 * 
	 * @param accountName
	 * @return
	 * @throws DeveloperConsoleException
	 * @throws AuthenticationException
	 * @throws MultiAccountAcception
	 * @throws NetworkException
	 * @throws JSONException
	 */
	public synchronized List<AppInfo> getAppInfo() throws DeveloperConsoleException,
			AuthenticationException, MultiAccountAcception, NetworkException {

		try {
			authenticate(false);
			// Fetch a list of available apps
			List<AppInfo> apps = fetchAppInfos();

			for (AppInfo app : apps) {
				// Fetch remaining app statistics
				// Latest stats object, and active device installs is already
				// setup
				AppStats stats = app.getLatestStats();
				fetchStatistics(app.getPackageName(), stats, STATS_TYPE_TOTAL_USER_INSTALLS);
				fetchRatings(app.getPackageName(), stats);
				stats.setNumberOfComments(fetchCommentsCount(app.getPackageName()));
			}

			return apps;
		} catch (JSONException e) {
			throw new DeveloperConsoleException(e);
		}
	}

	/**
	 * Gets a list of comments for the given app based on the startIndex and
	 * count
	 * 
	 * @param accountName
	 * @param packageName
	 * @param startIndex
	 * @param count
	 * @return
	 * @throws JSONException
	 * @throws NetworkException
	 * @throws MultiAccountAcception
	 * @throws AuthenticationException
	 * @throws DeveloperConsoleException
	 */
	public synchronized List<Comment> getComments(String packageName, int startIndex, int count)
			throws JSONException, AuthenticationException, MultiAccountAcception, NetworkException,
			DeveloperConsoleException {

		try {
			// First try using existing cookies and tokens
			authenticate(true);
			return fetchComments(packageName, startIndex, count);
		} catch (DeveloperConsoleException ex) {
			authenticate(false);
			return fetchComments(packageName, startIndex, count);
		}
	}

	/**
	 * Fetches a list of apps for the given account
	 * 
	 * @param accountName
	 * @return
	 * @throws DeveloperConsoleException
	 * @throws JSONException
	 */
	private List<AppInfo> fetchAppInfos() throws DeveloperConsoleException, JSONException {

		// Setup the request
		// TODO Check the remaining possible parameters to see if they are
		// needed for large numbers of apps
		String postData = String.format(PAYLOAD_APPS, authInfo.getXsrfToken());

		// Perform the request
		String json = null;
		try {
			json = post(createDeveloperUrl(URL_APPS), postData);

			return JsonParser.parseAppInfos(json, accountName);
		} catch (Exception ex) {
			throw new DeveloperConsoleException(json, ex);
		}
	}

	private String createDeveloperUrl(String baseUrl) {
		return String.format("%s?dev_acc=%s", baseUrl, authInfo.getDeveloperAccountId());
	}

	/**
	 * Fetches statistics for the given packageName of the given statsType and
	 * adds them to the given {@link AppStats} object
	 * 
	 * @param packageName
	 * @param stats
	 * @param statsType
	 * @throws DeveloperConsoleException
	 * @throws JSONException
	 */
	private void fetchStatistics(String packageName, AppStats stats, int statsType)
			throws DeveloperConsoleException, JSONException {

		// Setup the request
		// Don't care about the breakdown at the moment:
		// STATS_BY_ANDROID_VERSION
		String postData = String.format(PAYLOAD_STATISTICS, packageName, statsType,
				STATS_BY_ANDROID_VERSION, authInfo.getXsrfToken());

		// Perform the request
		String json = null;
		try {
			json = post(createDeveloperUrl(URL_STATISTICS), postData);

			JsonParser.parseStatistics(json, stats, statsType);
		} catch (Exception ex) {
			throw new DeveloperConsoleException(json, ex);
		}
	}

	/**
	 * Fetches ratings for the given packageName and adds them to the given
	 * {@link AppStats} object
	 * 
	 * @param packageName
	 *            The app to fetch ratings for
	 * @param stats
	 *            The AppStats object to add them to
	 * @throws DeveloperConsoleException
	 */
	private void fetchRatings(String packageName, AppStats stats) throws DeveloperConsoleException,
			JSONException {

		// Setup the request
		String postData = String.format(PAYLOAD_RATINGS, packageName, authInfo.getXsrfToken());

		// Perform the request
		String json = null;
		try {
			json = post(createDeveloperUrl(URL_REVIEWS), postData);

			JsonParser.parseRatings(json, stats);
		} catch (Exception ex) {
			throw new DeveloperConsoleException(json, ex);
		}
	}

	/**
	 * Fetches the number of comments for the given packageName
	 * 
	 * @param packageName
	 * @return
	 * @throws DeveloperConsoleException
	 * @throws JSONException
	 */
	private int fetchCommentsCount(String packageName) throws DeveloperConsoleException,
			JSONException {

		// Setup the request
		// TODO Check asking for 0 comments
		String postData = String.format(PAYLOAD_COMMENTS, packageName, 0, 1,
				authInfo.getXsrfToken());

		// Perform the request
		String json = null;
		try {
			json = post(createDeveloperUrl(URL_REVIEWS), postData);

			return JsonParser.parseCommentsCount(json);
		} catch (Exception ex) {
			throw new DeveloperConsoleException(json, ex);
		}
	}

	private List<Comment> fetchComments(String packageName, int startIndex, int count)
			throws DeveloperConsoleException, JSONException {

		// Setup the request
		String postData = String.format(PAYLOAD_COMMENTS, packageName, startIndex, count,
				authInfo.getXsrfToken());

		// Perform the request
		String json = null;
		try {
			json = post(createDeveloperUrl(URL_REVIEWS), postData);

			return JsonParser.parseComments(json);
		} catch (Exception ex) {
			throw new DeveloperConsoleException(json, ex);
		}
	}

	/**
	 * Logs into the Android Developer Console
	 * 
	 * @param reuseAuthentication
	 * @throws AuthenticationException
	 * @throws MultiAccountAcception
	 * @throws NetworkException
	 */
	// TODO revise exceptions
	private void authenticate(boolean reuseAuthentication) throws AuthenticationException,
			MultiAccountAcception, NetworkException {
		if (!reuseAuthentication) {
			authInfo = null;
		}

		if (authInfo != null) {
			// nothing to do
			return;
		}

		authInfo = authenticator.authenticate();
	}

	private String post(String url, String postData) throws IOException, ProtocolException {
		HttpPost post = new HttpPost(url);
		addHeaders(post);
		post.setEntity(new StringEntity(postData, "UTF-8"));

		if (DEBUG) {
			CookieStore cookieStore = httpClient.getCookieStore();
			List<Cookie> cookies = cookieStore.getCookies();
			for (Cookie c : cookies) {
				Log.d(TAG, String.format("****Cookie**** %s=%s", c.getName(), c.getValue()));
			}
		}

		// TODO maybe translate exceptions better?
		ResponseHandler<String> handler = HttpClientFactory
				.createResponseHandler(RuntimeException.class);

		return httpClient.execute(post, handler);
	}

	private void addHeaders(HttpPost post) {
		post.addHeader("Host", "play.google.com");
		post.addHeader("Connection", "keep-alive");
		post.addHeader("Content-Type", "application/json; charset=utf-8");
		post.addHeader("X-GWT-Permutation", "04C42FD45B1FCD2E3034C8A4DC5145C1");
		post.addHeader("X-GWT-Module-Base", "https://play.google.com/apps/publish/v2/gwt/");
		post.addHeader(
				"Referer",
				"https://play.google.com/apps/publish/v2/?dev_acc="
						+ authInfo.getDeveloperAccountId());
	}

}