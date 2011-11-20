package com.chaos.taxi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Iterator;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONException;
import org.json.JSONObject;

import com.chaos.taxi.map.TaxiMapView;
import com.chaos.taxi.map.TaxiOverlayItem.TaxiOverlayItemParam;
import com.chaos.taxi.map.UserOverlayItem.UserOverlayItemParam;
import com.google.android.maps.GeoPoint;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.location.Location;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

public class RequestProcessor {
	private static final String TAG = "RequestProcessor";
	static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
	static final String REGISTER_SUCESS = "REGISTER_SUCESS";
	static final String HTTPSERVER = "http://127.0.0.1:9000/passenger";

	static final int CALLSERVER_INTERVAL = 5000;
	static final float LOCATION_UPDATE_DISTANCE = (float) 5.0; // 5 meters
	static final int REQUEST_TIMEOUT_THRESHOLD = 30000;

	static final String CANCEL_CALL_TAXI_REQUEST = "cancel-call-taxi";
	static final String CALL_TAXI_REQUEST = "call-taxi";
	static final String CALL_TAXI_RESPONSE = "call-taxi-reply";
	static final String CALL_TAXI_COMPLETE = "call-taxi-complete";
	static final String FIND_TAXI_REQUEST = "FindTaxi";
	static final String LOCATION_UPDATE_REQUEST = "location-update";
	static final String REFRESH_REQUEST = "RefreshRequest";

	static boolean mStopSendRequestThread = false;

	static ArrayList<Request> mRequests = new ArrayList<Request>();
	static Context mContext = null;

	static Object mMapViewLock = new Object();
	static TaxiMapView mMapView = null;

	static Object mUserGeoPointLock = new Object();
	static GeoPoint mUserGeoPoint = null;
	static Object mCallTaxiLock = new Object();
	static TaxiOverlayItemParam mMyTaxiParam = null;
	static Integer mCallTaxiNumber = 1;

	static DefaultHttpClient mHttpClient = new DefaultHttpClient();
	static Thread mSendRequestThread = null;

	public static class Request {
		long mRequestTime;
		String mRequestType;
		JSONObject mRequestJson;
		Object mData;

		public Request(String requestType, JSONObject requestJson) {
			mRequestTime = System.currentTimeMillis();
			mRequestType = requestType;
			mRequestJson = requestJson;
			mData = null;
		}

		public int getIntData() {
			return (Integer) mData;
		}
	}

	public static void initRequestProcessor(Context context, TaxiMapView mapView) {
		mContext = context;
		mMapView = mapView;
	}

	public static void setUserGeoPoint(GeoPoint point) {
		if (point == null) {
			Log.d(TAG, "setUserGeoPoint: point is null!");
			return;
		}
		GeoPoint lastPoint = null;
		synchronized (mUserGeoPointLock) {
			lastPoint = mUserGeoPoint;
			mUserGeoPoint = point;
		}
		if (lastPoint != null) {
			Location last = TaxiUtil.geoPointToLocation(lastPoint);
			Location current = TaxiUtil.geoPointToLocation(point);
			if (last.distanceTo(current) >= LOCATION_UPDATE_DISTANCE) {
				addRequest(generateUserLocationUpdateRequest(point));
			}
		}
	}

	public static GeoPoint getUserGeoPoint() {
		synchronized (mUserGeoPointLock) {
			return mUserGeoPoint;
		}
	}

	public static TaxiOverlayItemParam getMyTaxiParam() {
		synchronized (mCallTaxiLock) {
			return mMyTaxiParam;
		}
	}

	private static void animateTo(GeoPoint point) {
		synchronized (mMapViewLock) {
			mMapView.getController().animateTo(point);
		}
	}

	private static void addRequest(Request request, Object data) {
		if (request == null) {
			Log.d(TAG, "request is null!");
			return;
		}
		try {
			request.mRequestJson.put("request_type", request.mRequestType);
		} catch (JSONException e) {
			Log.e(TAG, "cannot put request_type into request! "
					+ request.mRequestType);
			e.printStackTrace();
			return;
		}

		request.mData = data;
		synchronized (mRequests) {
			for (int i = 0; i < mRequests.size(); ++i) {
				if (mRequests.get(i).mRequestType.equals(request.mRequestType)) {
					mRequests.set(i, request);
				}
			}
			mRequests.add(request);
		}
	}

	private static void addRequest(Request request) {
		addRequest(request, null);
	}

	private static boolean removeRequest(String requestType) {
		Log.i(TAG, "removeRequst: " + requestType);
		if (requestType == null) {
			return false;
		}
		synchronized (mRequests) {
			Iterator<Request> iter = mRequests.iterator();
			while (iter.hasNext()) {
				if (requestType.equals(iter.next().mRequestType)) {
					mRequests.remove(iter);
					return true;
				}
			}
			return false;
		}
	}

	private static Request getRequest() {
		synchronized (mRequests) {
			if (mRequests.size() > 0) {
				Request request = mRequests.get(0);
				mRequests.remove(0);
				return request;
			} else {
				return null;
			}
		}
	}

	public static void sendLocateUserRequest() {
		GeoPoint point = getUserGeoPoint();
		if (point != null) {
			animateTo(point);
			synchronized (mMapViewLock) {
				mMapView.showUserOverlay(new UserOverlayItemParam(point));
			}
		} else {
			Toast.makeText(mContext, "Waiting for locate", 4000).show();
		}
	}

	public static void sendLocateTaxiRequest() {
		Log.d(TAG, "sendLocateTaxiRequest");
		TaxiOverlayItemParam param = getMyTaxiParam();
		if (param != null && param.mPoint != null) {
			animateTo(param.mPoint);
			synchronized (mMapViewLock) {
				mMapView.removeAroundOverlay();
				mMapView.showMyTaxiOverlay(param);
			}
		} else {
			Toast.makeText(mContext, "Waiting for taxi locate", 4000).show();
		}
	}

	public static void sendFindTaxiRequest() {
		Pair<Integer, JSONObject> httpRet = sendRequestToServer(generateFindTaxiRequest());
		if (httpRet == null) {
			return;
		}

		// handle the find taxi result
		if (httpRet.first == 0) {
			JSONObject jsonRet = httpRet.second;
			JSONObject taxis;
			try {
				taxis = jsonRet.getJSONObject("taxis");
				if (taxis != null) {
					@SuppressWarnings("unchecked")
					Iterator<String> iter = taxis.keys();
					while (iter.hasNext()) {
						String key = iter.next();
						JSONObject taxiInfo = taxis.getJSONObject(key);
						GeoPoint point = new GeoPoint(
								taxiInfo.getInt("latitude"),
								taxiInfo.getInt("longitude"));
						String carNumber = taxiInfo.getString("car_number");
						String phoneNumber = taxiInfo.getString("phone_number");
						String nickName = taxiInfo.getString("nickname");
						TaxiOverlayItemParam param = new TaxiOverlayItemParam(
								point, carNumber, phoneNumber, nickName);
						synchronized (mMapViewLock) {
							mMapView.addAroundTaxiOverlay(param);
						}
					}
				}
			} catch (JSONException e) {
				e.printStackTrace();
			}
		}
	}

	public static void cancelCallTaxiRequest() {
		Request request = null;
		synchronized (mCallTaxiLock) {
			mMyTaxiParam = null;
			if (!removeRequest(CALL_TAXI_REQUEST))
				request = generateCancelCallTaxiRequest(mCallTaxiNumber);
			++mCallTaxiNumber;
		}
		if (request != null) {
			final Request req = request;
			new Thread(new Runnable() {
				public void run() {
					while (true) {
						Pair<Integer, JSONObject> ret = sendRequestToServer(req);
						if (ret != null) {
							if (ret.first == 0) {
								Log.d(TAG, "cancel taxi request succeed!");
								return;
							} else {

								// TODO: here for some status do not need to
								// continue
								String message = null;
								try {
									message = ret.second.getString("message");
								} catch (JSONException e) {
									e.printStackTrace();
									message = "CancelCallTaxi IS FAILED!";
								}
								Log.d(TAG, ret.first + " " + message);
							}
						}
					}
				}
			}).start();
		}
	}

	public static void sendCallTaxiRequest(String taxiPhoneNumber) {
		synchronized (mCallTaxiLock) {
			if (mMyTaxiParam != null) {
				AlertDialog dialog = new AlertDialog.Builder(mContext)
						.setIcon(android.R.drawable.ic_dialog_info)
						.setTitle("CallTaxiFail: ")
						.setMessage("Already have a taxi")
						.setPositiveButton("Locate", new OnClickListener() {
							public void onClick(DialogInterface dialog,
									int which) {
								sendLocateTaxiRequest();
							}
						}).setNegativeButton("OK", null).create();
				dialog.show();
				return;
			} else {
				++mCallTaxiNumber;
				addRequest(generateCallTaxiRequest(mCallTaxiNumber,
						taxiPhoneNumber));
			}
		}
	}

	public static void sendCallTaxiRequest() {
		sendCallTaxiRequest(null);
	}

	public static void showCallTaxiSucceedDialog() {
		synchronized (mCallTaxiLock) {
			if (mMyTaxiParam != null) {
				AlertDialog dialog = new AlertDialog.Builder(mContext)
						.setIcon(android.R.drawable.ic_dialog_info)
						.setTitle("CallTaxiSucceed: ")
						.setMessage(
								"CarNumber is " + mMyTaxiParam.mCarNumber
										+ "\nPhoneNumber is "
										+ mMyTaxiParam.mPhoneNumber
										+ "\nNickName is "
										+ mMyTaxiParam.mNickName)
						.setPositiveButton("Locate", new OnClickListener() {
							public void onClick(DialogInterface dialog,
									int which) {
								sendLocateTaxiRequest();
							}
						}).setNegativeButton("OK", null).create();
				dialog.show();
			} else {
				Log.wtf(TAG, "taxi should not be null!");
			}
		}
	}

	public static boolean isCallTaxiSucceed() {
		synchronized (mCallTaxiLock) {
			return (mMyTaxiParam != null);
		}
	}

	public static void logout() {
		mStopSendRequestThread = true;
		try {
			mSendRequestThread.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		// TODO: logout
		mHttpClient.getConnectionManager().shutdown();
	}

	public static String login(String nickName, String password) {
		TaxiActivity.sNickName = nickName;

		HttpPost httpPost = new HttpPost(HTTPSERVER + "/signin");
		JSONObject signinJson = new JSONObject();
		try {
			signinJson.put("nick_name", nickName);
			signinJson.put("password", password);
			httpPost.setEntity(new StringEntity(signinJson.toString()));
		} catch (JSONException e) {
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		Pair<Integer, JSONObject> executeRet = executeHttpRequest(httpPost);
		String message = LOGIN_SUCCESS;
		if (executeRet.first != 0) {
			Log.e(TAG, "login fail, status code is " + executeRet.first);
			try {
				message = executeRet.second.getString("message");
			} catch (JSONException e) {
				e.printStackTrace();
				message = "LOGIN IS FAILED!";
			}
		} else {
			mSendRequestThread = new Thread(mTask);
			mStopSendRequestThread = false;
			mSendRequestThread.start();
		}
		// return LOGIN_SUCCESS;
		return message;
	}

	public static String register(String nickName, String userName,
			String password) {
		// TODO: register
		return null;
	}

	private static void resendRequest(Request request) {
		// sleep 1 second before resend
		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		if (request.mRequestType.equals(CALL_TAXI_REQUEST)) {
			synchronized (mCallTaxiLock) {
				if (request.getIntData() == mCallTaxiNumber) {
					addRequest(request);
				}
			}
		} else if (request.mRequestType.equals(LOCATION_UPDATE_REQUEST)) {
			addRequest(generateUserLocationUpdateRequest(getUserGeoPoint()));
		}
	}

	private static Request generateFindTaxiRequest() {
		GeoPoint userPoint = getUserGeoPoint();
		if (userPoint == null) {
			Log.w(TAG,
					"user geoPoint not updated! cannot send FindTaxi request!");
			return null;
		}

		JSONObject jsonObj = new JSONObject();
		try {
			jsonObj.put("latitude", userPoint.getLatitudeE6());
			jsonObj.put("Longitude", userPoint.getLongitudeE6());
		} catch (JSONException e) {
			e.printStackTrace();
			return null;
		}
		return new Request(FIND_TAXI_REQUEST, jsonObj);
	}

	private static Request generateCancelCallTaxiRequest(int callTaxiNumber) {
		JSONObject jsonObj = new JSONObject();
		try {
			jsonObj.put("type", CANCEL_CALL_TAXI_REQUEST);
			jsonObj.put("from", "1234567890");
			jsonObj.put("number", callTaxiNumber);
		} catch (JSONException e) {
			e.printStackTrace();
			return null;
		}
		Request request = new Request(CANCEL_CALL_TAXI_REQUEST, jsonObj);
		request.mData = (Integer) callTaxiNumber;
		return request;
	}

	private static Request generateCallTaxiRequest(int callTaxiNumber,
			String taxiPhoneNumber) {
		Log.d(TAG, "generateCallTaxiRequest, callTaxiNumber: " + callTaxiNumber
				+ " taxiPhoneNumber: " + taxiPhoneNumber);
		JSONObject jsonObj = new JSONObject();
		GeoPoint userPoint = getUserGeoPoint();
		try {
			jsonObj.put("type", CALL_TAXI_REQUEST);
			jsonObj.put("from", "1234567890");
			jsonObj.put("number", mCallTaxiNumber);
			if (taxiPhoneNumber != null) {
				jsonObj.put("to", taxiPhoneNumber);
			}
			if (userPoint != null) {
				jsonObj.put("latitude", userPoint.getLatitudeE6());
				jsonObj.put("Longitude", userPoint.getLongitudeE6());
			}
		} catch (JSONException e) {
			e.printStackTrace();
			return null;
		}
		Request request = new Request(CALL_TAXI_REQUEST, jsonObj);
		request.mData = (Integer) callTaxiNumber;
		return request;
	}

	private static Request generateUserLocationUpdateRequest(GeoPoint point) {
		if (point == null) {
			Log.i(TAG, "null point do not need update!");
			return null;
		}
		JSONObject jsonObj = new JSONObject();
		try {
			jsonObj.put("latitude", point.getLatitudeE6());
			jsonObj.put("Longitude", point.getLongitudeE6());
		} catch (JSONException e) {
			e.printStackTrace();
			return null;
		}
		return new Request(LOCATION_UPDATE_REQUEST, jsonObj);
	}

	private static Pair<Integer, JSONObject> executeHttpRequest(
			HttpUriRequest httpUriRequest) {
		Integer statusCode = 0;
		String exceptionMsg = null;

		try {
			HttpResponse httpResponse = null;
			synchronized (mHttpClient) {
				httpResponse = mHttpClient.execute(httpUriRequest);
			}
			statusCode = httpResponse.getStatusLine().getStatusCode();
			if (statusCode == 0) {
				BufferedReader bufferedReader = new BufferedReader(
						new InputStreamReader(httpResponse.getEntity()
								.getContent()));
				StringBuffer stringBuffer = new StringBuffer();
				for (String line = bufferedReader.readLine(); line != null; line = bufferedReader
						.readLine()) {
					stringBuffer.append(line);
				}

				String str = stringBuffer.toString();
				Log.d(TAG, "response is " + str);
				if (str == null) {
					return new Pair<Integer, JSONObject>(statusCode, null);
				} else {
					return new Pair<Integer, JSONObject>(statusCode,
							new JSONObject(str));
				}
			} else {
				Log.w(TAG, "HttpFail. HttpPost StatusCode is " + statusCode);
				return new Pair<Integer, JSONObject>(statusCode, null);
			}
		} catch (ClientProtocolException e) {
			exceptionMsg = "ClientProtocolException: " + e.getMessage();
			e.printStackTrace();
		} catch (IOException e) {
			exceptionMsg = "IOException: " + e.getMessage();
			e.printStackTrace();
		} catch (JSONException e) {
			exceptionMsg = "JSONException: " + e.getMessage();
			e.printStackTrace();
		}

		JSONObject jsonRet = new JSONObject();
		try {
			// TODO: the detail message may need to be removed in final revision
			jsonRet.put("message", "Cannot conenct to server!" + exceptionMsg);
		} catch (JSONException e) {
			e.printStackTrace();
		}
		return new Pair<Integer, JSONObject>(-1, jsonRet);
	}

	private static Pair<Integer, JSONObject> sendRequestToServer(Request request) {
		HttpUriRequest httpUriRequest = TaxiUtil
				.generateHttpUriRequest(request);
		if (httpUriRequest == null) {
			Log.wtf(TAG, "Cannot generate HttpUriRequest for request: "
					+ request.mRequestJson.toString());
			return null;
		}

		return executeHttpRequest(httpUriRequest);
	}

	static Runnable mTask = new Runnable() {
		public void run() {
			int count = 0;
			while (true) {
				if (mStopSendRequestThread) {
					Log.d(TAG, "stop send request thread!");
					return;
				} else {
					++count;
					Log.d(TAG, "send request count: " + count);
				}

				Request request = getRequest();
				if (request == null) {
					sendRefreshRequestToServer();
					try {
						Thread.sleep(CALLSERVER_INTERVAL);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					continue;
				}

				Pair<Integer, JSONObject> httpRet = sendRequestToServer(request);
				if (httpRet == null) {
					continue;
				}
				if (httpRet.first != 0) {
					// TODO: some status may do not need resend
					resendRequest(request);
				}
			}
		}
	};

	private static void sendRefreshRequestToServer() {
		Request request = new Request(REFRESH_REQUEST, null);

		Pair<Integer, JSONObject> httpRet = sendRequestToServer(request);
		if (httpRet == null) {
			return;
		}
		Log.d(TAG, "refresh request status is " + httpRet.first);
		if (httpRet.first != 0) {
			return;
		}
		// handle the result
		handleRefreshResponseJson(httpRet.second);
	}

	private static void handleRefreshResponseJson(JSONObject jsonRet) {
		if (jsonRet == null) {
			return;
		}
		JSONObject messageListJson = jsonRet.optJSONObject("message");
		if (messageListJson == null) {
			Log.d(TAG, "no message in refresh response!");
			return;
		}

		JSONObject callTaxiReplyJson = messageListJson
				.optJSONObject(CALL_TAXI_RESPONSE);
		if (callTaxiReplyJson != null) {
			handleCallTaxiReplyJson(callTaxiReplyJson);
		}

		JSONObject taxiLocationJson = messageListJson
				.optJSONObject(LOCATION_UPDATE_REQUEST);
		if (taxiLocationJson != null) {
			handleTaxiLocationUpdate(taxiLocationJson);
		}

		JSONObject callTaxiCompleteJson = messageListJson
				.optJSONObject(CALL_TAXI_COMPLETE);
		if (callTaxiCompleteJson != null) {
			handleCallTaxiComplete(callTaxiCompleteJson);
		}
	}

	private static void handleCallTaxiComplete(JSONObject callTaxiCompleteJson) {
		synchronized (mCallTaxiLock) {
			if (mMyTaxiParam == null) {
				Log.w(TAG, "handleCallTaxiComplete: do not have a taxi!");
				return;
			} else {
				Intent intent = new Intent(mContext,
						CallTaxiCompleteActivity.class);
				intent.putExtra("TaxiParam", mMyTaxiParam);
				mMyTaxiParam = null;
				mContext.startActivity(intent);
			}
		}
	}

	private static void handleTaxiLocationUpdate(JSONObject taxiLocationJson) {
		synchronized (mCallTaxiLock) {
			if (mMyTaxiParam == null) {
				Log.w(TAG, "handleTaxiLocationUpdate: do not have a taxi!");
				return;
			} else {
				try {
					int latitude = taxiLocationJson.getInt("latitude");
					int longitude = taxiLocationJson.getInt("longitude");
					mMyTaxiParam.mPoint = new GeoPoint(latitude, longitude);
				} catch (JSONException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private static void handleCallTaxiReplyJson(JSONObject callTaxiReplyJson) {
		Log.d(TAG, "handle call taxi reply!");
		String taxiPhoneNumber = callTaxiReplyJson.optString("from");
		if (taxiPhoneNumber == null) {
			Log.wtf(TAG, "taxiPhoneNumber is null in call taxi reply!!!");
			return;
		}
		Log.d(TAG, "taxiPhoneNumber is " + taxiPhoneNumber);

		int callTaxiNumber = callTaxiReplyJson.optInt("number", -1);
		synchronized (mCallTaxiLock) {
			if (callTaxiNumber < mCallTaxiNumber) {
				Log.w(TAG, "ignore call taxi response: " + callTaxiNumber
						+ " currentNumber is " + mCallTaxiNumber);
			} else {
				if (callTaxiNumber > mCallTaxiNumber) {
					Log.wtf(TAG, "callTaxiNumber " + callTaxiNumber
							+ " should not be larger than " + mCallTaxiNumber);
				} else {
					synchronized (mMapViewLock) {
						mMyTaxiParam = mMapView
								.findInAroundTaxi(taxiPhoneNumber);
					}
				}

				if (mMyTaxiParam != null) {
					showCallTaxiSucceedDialog();
				}
			}
		}
	}
}
