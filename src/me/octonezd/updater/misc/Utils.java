/*
 * Copyright (C) 2017-2022 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package me.octonezd.updater.misc;

import android.app.AlarmManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.util.Log;
import android.widget.Toast;
import androidx.preference.PreferenceManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import me.octonezd.updater.R;
import me.octonezd.updater.UpdatesDbHelper;
import me.octonezd.updater.controller.UpdaterService;
import me.octonezd.updater.model.Update;
import me.octonezd.updater.model.UpdateBaseInfo;
import me.octonezd.updater.model.UpdateInfo;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import com.github.underscore.U;

public class Utils {

  private static final String TAG = "Utils";

  private Utils() {}

  public static File getDownloadPath(Context context) {
    return new File(context.getString(R.string.download_path));
  }

  public static File getExportPath(Context context) {
    File dir = new File(
      context.getExternalFilesDir(null),
      context.getString(R.string.export_path)
    );
    if (!dir.isDirectory()) {
      if (dir.exists() || !dir.mkdirs()) {
        throw new RuntimeException("Could not create directory");
      }
    }
    return dir;
  }

  public static File getCachedUpdateList(Context context) {
    return new File(context.getCacheDir(), "updates.json");
  }

  private static UpdateInfo parseS3Update(JSONObject object) 
  throws JSONException {
    // Sample file key:
    // surya/PixelExperience_Plus_surya-13.0-20230520-1550-NIS-signed.zip
    Update update = new Update();
    update.setFileSize(object.getLong("Size"));
    update.setName(object.getString("Key").split("/")[1]);
    update.setDownloadId(object.getString("ETag"));
    String[] fileparts = object.getString("Key").replace(".zip", "").split("-");
    update.setVersion(fileparts[1]);
    DateTimeFormatter dateformatter = DateTimeFormatter.ofPattern("yyyyMMdd");
    DateTimeFormatter timeformatter = DateTimeFormatter.ofPattern("HHmm");
    LocalDate datePart = LocalDate.parse(fileparts[2], dateformatter);
    LocalTime timePart = LocalTime.parse(fileparts[3], timeformatter);
    LocalDateTime dt = LocalDateTime.of(datePart, timePart);
    ZoneOffset zone = ZoneOffset.of("Z");
    // We subtract a minute, because of edge cases where CUSTOM_VERSION
    // might get bumped up to one minute higher
    // than it should be
    long epochtime = dt.toEpochSecond(zone) - 60;
    Log.d(TAG, "epoch time: " + epochtime);
    update.setTimestamp(epochtime);
    update.setType(fileparts[4]);
    update.setDownloadUrl(getBucketURL() + object.getString("Key"));
    return update;
  }

  public static boolean isCompatible(UpdateBaseInfo update) {
    if (
      update
        .getVersion()
        .compareTo(SystemProperties.get(Constants.PROP_BUILD_VERSION)) <
      0
    ) {
      Log.d(TAG, update.getName() + " is older than current Android version");
      return false;
    }
    if (
      !SystemProperties.getBoolean(
        Constants.PROP_UPDATER_ALLOW_DOWNGRADING,
        false
      ) &&
      update.getTimestamp() <=
      SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)
    ) {
      Log.d(
        TAG,
        update.getName() + " is older than/equal to the current build"
      );
      return false;
    }
    if (
      !update
        .getType()
        .equalsIgnoreCase(SystemProperties.get(Constants.PROP_RELEASE_TYPE))
    ) {
      Log.d(TAG, update.getName() + " has type " + update.getType());
      return false;
    }
    Log.d(TAG, update.getName() + " is compatible, ts:" + update.getTimestamp());
    return true;
  }

  public static boolean canInstall(UpdateBaseInfo update) {
    return (
      (
        SystemProperties.getBoolean(
          Constants.PROP_UPDATER_ALLOW_DOWNGRADING,
          false
        ) ||
        update.getTimestamp() >
        SystemProperties.getLong(Constants.PROP_BUILD_DATE, 0)
      ) &&
      update
        .getVersion()
        .equalsIgnoreCase(SystemProperties.get(Constants.PROP_BUILD_VERSION))
    );
  }

  public static List<UpdateInfo> parseS3XML(File file, boolean compatibleOnly)
  throws IOException, JSONException {
    List<UpdateInfo> updates = new ArrayList<>();
    StringBuilder xml = new StringBuilder();
    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
      for (String line; (line = br.readLine()) != null;) {
        xml.append(line);
      }
    }

    JSONObject obj = new JSONObject(U.xmlToJson(xml.toString()));
    Log.d(TAG, "converted to json:" + obj);
    Object listBucketResTmp = obj.getJSONObject("ListBucketResult").get("Contents");
    JSONArray updatesList = new JSONArray();
    if (listBucketResTmp instanceof JSONObject) {
      updatesList.put(listBucketResTmp);
    } else {
      updatesList = (JSONArray) listBucketResTmp;
    }
    for (int i = 0; i < updatesList.length(); i++) {
      if (updatesList.isNull(i)) {
        continue;
      }
      try {
        UpdateInfo update = parseS3Update(updatesList.getJSONObject(i));
        if (!compatibleOnly || isCompatible(update)) {
          updates.add(update);
        } else {
          Log.d(TAG, "Ignoring incompatible update " + update.getName());
        }
      } catch (JSONException e) {
        Log.e(TAG, "Could not parse update object, index=" + i, e);
      }
    }

    return updates;
  }

  public static String getBucketURL() {
    String endpoint = SystemProperties.get(Constants.PROP_S3_ENDPOINT);
    String bucket = SystemProperties.get(Constants.PROP_S3_BUCKET);
    return endpoint + "/" + bucket + "/";
  }

  public static String getServerURL(Context context) {
    String device = SystemProperties.get(
      Constants.PROP_NEXT_DEVICE,
      SystemProperties.get(Constants.PROP_DEVICE)
    );
    Log.d(TAG, "device is" + device);
    String serverUrl = getBucketURL() + "?prefix=" + device;

    return serverUrl;
  }

  public static String getUpgradeBlockedURL(Context context) {
    String device = SystemProperties.get(
      Constants.PROP_NEXT_DEVICE,
      SystemProperties.get(Constants.PROP_DEVICE)
    );
    return context.getString(R.string.blocked_update_info_url, getBucketURL(), device);
  }

  public static String getChangelogURL(Context context) {
    return context.getString(R.string.menu_changelog_url, getBucketURL());
  }

  public static void triggerUpdate(Context context, String downloadId) {
    final Intent intent = new Intent(context, UpdaterService.class);
    intent.setAction(UpdaterService.ACTION_INSTALL_UPDATE);
    intent.putExtra(UpdaterService.EXTRA_DOWNLOAD_ID, downloadId);
    context.startService(intent);
  }

  public static boolean isNetworkAvailable(Context context) {
    ConnectivityManager cm = context.getSystemService(
      ConnectivityManager.class
    );
    NetworkInfo info = cm.getActiveNetworkInfo();
    return !(info == null || !info.isConnected() || !info.isAvailable());
  }

  public static boolean isOnWifiOrEthernet(Context context) {
    ConnectivityManager cm = context.getSystemService(
      ConnectivityManager.class
    );
    NetworkInfo info = cm.getActiveNetworkInfo();
    return (
      info != null &&
      (
        info.getType() == ConnectivityManager.TYPE_ETHERNET ||
        info.getType() == ConnectivityManager.TYPE_WIFI
      )
    );
  }

  /**
   * Compares two json formatted updates list files
   *
   * @param oldS3XML old update list
   * @param newS3XML new update list
   * @return true if newS3XML has at least a compatible update not available in oldS3XML
   */
  public static boolean checkForNewUpdates(File oldS3XML, File newS3XML)
    throws IOException, JSONException {
    List<UpdateInfo> oldList = parseS3XML(oldS3XML, true);
    List<UpdateInfo> newList = parseS3XML(newS3XML, true);
    Set<String> oldIds = new HashSet<>();
    for (UpdateInfo update : oldList) {
      oldIds.add(update.getDownloadId());
    }
    // In case of no new updates, the old list should
    // have all (if not more) the updates
    for (UpdateInfo update : newList) {
      if (!oldIds.contains(update.getDownloadId())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Get the offset to the compressed data of a file inside the given zip
   *
   * @param zipFile input zip file
   * @param entryPath full path of the entry
   * @return the offset of the compressed, or -1 if not found
   * @throws IllegalArgumentException if the given entry is not found
   */
  public static long getZipEntryOffset(ZipFile zipFile, String entryPath) {
    // Each entry has an header of (30 + n + m) bytes
    // 'n' is the length of the file name
    // 'm' is the length of the extra field
    final int FIXED_HEADER_SIZE = 30;
    Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
    long offset = 0;
    while (zipEntries.hasMoreElements()) {
      ZipEntry entry = zipEntries.nextElement();
      int n = entry.getName().length();
      int m = entry.getExtra() == null ? 0 : entry.getExtra().length;
      int headerSize = FIXED_HEADER_SIZE + n + m;
      offset += headerSize;
      if (entry.getName().equals(entryPath)) {
        return offset;
      }
      offset += entry.getCompressedSize();
    }
    Log.e(TAG, "Entry " + entryPath + " not found");
    throw new IllegalArgumentException("The given entry was not found");
  }

  public static void removeUncryptFiles(File downloadPath) {
    File[] uncryptFiles = downloadPath.listFiles((dir, name) ->
      name.endsWith(Constants.UNCRYPT_FILE_EXT)
    );
    if (uncryptFiles == null) {
      return;
    }
    for (File file : uncryptFiles) {
      //noinspection ResultOfMethodCallIgnored
      file.delete();
    }
  }

  /**
   * Cleanup the download directory, which is assumed to be a privileged location
   * the user can't access and that might have stale files. This can happen if
   * the data of the application are wiped.
   *
   */
  public static void cleanupDownloadsDir(Context context) {
    File downloadPath = getDownloadPath(context);
    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(
      context
    );

    removeUncryptFiles(downloadPath);

    long buildTimestamp = SystemProperties.getLong(
      Constants.PROP_BUILD_DATE,
      0
    );
    long prevTimestamp = preferences.getLong(
      Constants.PREF_INSTALL_OLD_TIMESTAMP,
      0
    );
    String lastUpdatePath = preferences.getString(
      Constants.PREF_INSTALL_PACKAGE_PATH,
      null
    );
    boolean reinstalling = preferences.getBoolean(
      Constants.PREF_INSTALL_AGAIN,
      false
    );
    boolean deleteUpdates = preferences.getBoolean(
      Constants.PREF_AUTO_DELETE_UPDATES,
      false
    );
    if (
      (buildTimestamp != prevTimestamp || reinstalling) &&
      deleteUpdates &&
      lastUpdatePath != null
    ) {
      File lastUpdate = new File(lastUpdatePath);
      if (lastUpdate.exists()) {
        //noinspection ResultOfMethodCallIgnored
        lastUpdate.delete();
        // Remove the pref not to delete the file if re-downloaded
        preferences.edit().remove(Constants.PREF_INSTALL_PACKAGE_PATH).apply();
      }
    }

    final String DOWNLOADS_CLEANUP_DONE = "cleanup_done";
    if (preferences.getBoolean(DOWNLOADS_CLEANUP_DONE, false)) {
      return;
    }

    Log.d(TAG, "Cleaning " + downloadPath);
    if (!downloadPath.isDirectory()) {
      return;
    }
    File[] files = downloadPath.listFiles();
    if (files == null) {
      return;
    }

    // Ideally the database is empty when we get here
    UpdatesDbHelper dbHelper = new UpdatesDbHelper(context);
    List<String> knownPaths = new ArrayList<>();
    for (UpdateInfo update : dbHelper.getUpdates()) {
      knownPaths.add(update.getFile().getAbsolutePath());
    }
    for (File file : files) {
      if (!knownPaths.contains(file.getAbsolutePath())) {
        Log.d(TAG, "Deleting " + file.getAbsolutePath());
        //noinspection ResultOfMethodCallIgnored
        file.delete();
      }
    }

    preferences.edit().putBoolean(DOWNLOADS_CLEANUP_DONE, true).apply();
  }

  public static File appendSequentialNumber(final File file) {
    String name;
    String extension;
    int extensionPosition = file.getName().lastIndexOf(".");
    if (extensionPosition > 0) {
      name = file.getName().substring(0, extensionPosition);
      extension = file.getName().substring(extensionPosition);
    } else {
      name = file.getName();
      extension = "";
    }
    final File parent = file.getParentFile();
    for (int i = 1; i < Integer.MAX_VALUE; i++) {
      File newFile = new File(parent, name + "-" + i + extension);
      if (!newFile.exists()) {
        return newFile;
      }
    }
    throw new IllegalStateException();
  }

  public static boolean isABDevice() {
    return SystemProperties.getBoolean(Constants.PROP_AB_DEVICE, false);
  }

  public static boolean isABUpdate(ZipFile zipFile) {
    return (
      zipFile.getEntry(Constants.AB_PAYLOAD_BIN_PATH) != null &&
      zipFile.getEntry(Constants.AB_PAYLOAD_PROPERTIES_PATH) != null
    );
  }

  public static boolean isABUpdate(File file) throws IOException {
    ZipFile zipFile = new ZipFile(file);
    boolean isAB = isABUpdate(zipFile);
    zipFile.close();
    return isAB;
  }

  public static boolean hasTouchscreen(Context context) {
    return context
      .getPackageManager()
      .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
  }

  public static void addToClipboard(
    Context context,
    String label,
    String text,
    String toastMessage
  ) {
    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(
      Context.CLIPBOARD_SERVICE
    );
    ClipData clip = ClipData.newPlainText(label, text);
    clipboard.setPrimaryClip(clip);
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show();
  }

  public static boolean isEncrypted(Context context, File file) {
    StorageManager sm = (StorageManager) context.getSystemService(
      Context.STORAGE_SERVICE
    );
    return sm.isEncrypted(file);
  }

  public static int getUpdateCheckSetting(Context context) {
    SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(
      context
    );
    return preferences.getInt(
      Constants.PREF_AUTO_UPDATES_CHECK_INTERVAL,
      Constants.AUTO_UPDATES_CHECK_INTERVAL_WEEKLY
    );
  }

  public static boolean isUpdateCheckEnabled(Context context) {
    return (
      getUpdateCheckSetting(context) !=
      Constants.AUTO_UPDATES_CHECK_INTERVAL_NEVER
    );
  }

  public static long getUpdateCheckInterval(Context context) {
    switch (Utils.getUpdateCheckSetting(context)) {
      case Constants.AUTO_UPDATES_CHECK_INTERVAL_DAILY:
        return AlarmManager.INTERVAL_DAY;
      case Constants.AUTO_UPDATES_CHECK_INTERVAL_WEEKLY:
      default:
        return AlarmManager.INTERVAL_DAY * 7;
      case Constants.AUTO_UPDATES_CHECK_INTERVAL_MONTHLY:
        return AlarmManager.INTERVAL_DAY * 30;
    }
  }

  public static boolean isRecoveryUpdateExecPresent() {
    return new File(Constants.UPDATE_RECOVERY_EXEC).exists();
  }
}
