package org.csgeeks.TinyG;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

import org.csgeeks.TinyG.JogFragment.JogFragmentListener;
import org.csgeeks.TinyG.Support.TinyGService;

import com.actionbarsherlock.app.SherlockFragment;
import com.lamerman.FileDialog;
import com.lamerman.SelectionMode;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TextView.BufferType;
import android.widget.Toast;

public class FileFragment extends SherlockFragment {
	private static final String TAG = "TinyG";
	private JogFragmentListener parent;
	private Button startButton;
	private String filename;
	private EditText fileView;
	private SharedPreferences settings;
	private long numLines;
	private volatile int currentLinenum;
	private TextView fileContent;
	private ScrollView fileScroll;
	private RandomAccessFile gcodeFile;
	private boolean active = false;
	private ForegroundColorSpan activeSpan;

	@Override
	public void onAttach(Activity activity) {
		super.onAttach(activity);
		try {
			parent = (JogFragmentListener) activity;
		} catch (ClassCastException e) {
			throw new ClassCastException(activity.toString()
					+ " must implement JogFragmentListener");
		}
		settings = PreferenceManager.getDefaultSharedPreferences(activity);
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putString("filename", filename);

		SharedPreferences.Editor editor = settings.edit();
		editor.putString("filename", filename);
		editor.commit();
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container,
			Bundle savedInstanceState) {
		// Inflate the layout for this fragment
		View v = inflater.inflate(R.layout.gcodefile, container, false);

		if (savedInstanceState != null)
			filename = savedInstanceState.getString("filename");
		else {
			filename = settings.getString("filename", Environment
					.getExternalStorageDirectory().getPath() + "/test.nc");
		}

		fileView = (EditText) v.findViewById(R.id.filename);
		fileView.setText(filename);
		startButton = (Button) v.findViewById(R.id.start);
		fileContent = (TextView) v.findViewById(R.id.fileContent);
		fileScroll = (ScrollView) v.findViewById(R.id.fileScroll);
		activeSpan = new ForegroundColorSpan(Color.RED);
		
		openFile();
		return v;
	}

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode != 1)
			return;
		if (resultCode == android.app.Activity.RESULT_OK && data != null) {
			String newname = data.getStringExtra(FileDialog.RESULT_PATH);
			if (newname != null) {
				filename = newname;
				fileView.setText(newname);
				try {
					if (gcodeFile != null)
						gcodeFile.close();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				openFile();
			}
		}
	}

	public void myClickHandler(View view) {
		switch (view.getId()) {
		case R.id.filepick:
			pickFile();
			break;
		case R.id.start:
			if (active) {
				cancel();
			} else {
				queueFile();
				updateState(true);
			}
			break;
		}
	}

	private void pickFile() {
		Intent intent = new Intent(getActivity(), FileDialog.class);

		intent.putExtra(FileDialog.START_PATH, Environment
				.getExternalStorageDirectory().getPath());
		intent.putExtra(FileDialog.SELECTION_MODE, SelectionMode.MODE_OPEN);

		try {
			startActivityForResult(intent, 1);
		} catch (ActivityNotFoundException e) {
			// No compatible file manager was found.
			Toast.makeText(getActivity(), R.string.no_filemanager_installed,
					Toast.LENGTH_SHORT).show();
		}
	}

	public void updateState(boolean download) {
		active = download;
		if (download)
			startButton.setText(R.string.stop_label);
		else
			startButton.setText(R.string.start);
	}
	
	public void nextLine(int statusLine) {
		int start;
		int end;
		Spannable span;
		
		if (statusLine == currentLinenum)
			return;
		
		span = (Spannable)fileContent.getText();
		span.removeSpan(activeSpan);
		start = fileContent.getLayout().getLineStart(statusLine-1);
		end = fileContent.getLayout().getLineEnd(statusLine-1);
		span.setSpan(activeSpan, start, end, Spannable.SPAN_PARAGRAPH);
		currentLinenum = statusLine;
		
		if (currentLinenum > 10) {
			if (parent.queueSize() == 0)
				updateState(false);
			fileScroll.post(new Runnable() {
				public void run() {
					int y = fileContent.getLayout().getLineTop(currentLinenum-10);
					fileScroll.scrollTo(0, y);
				}
			});
		}
	}
	
	private void openFile() {
		String line, buf;
		
		// Count lines
		try {
			gcodeFile = new RandomAccessFile(filename, "r");
			numLines = 0;
			buf = "";
			while ((line = gcodeFile.readLine()) != null) {
				numLines++;
				buf += line + "\n";
			}
			currentLinenum = 0;
			SpannableStringBuilder stringBuilder = new SpannableStringBuilder(buf);
			fileContent.setText(stringBuilder, BufferType.SPANNABLE);
		} catch (FileNotFoundException e) {
			Toast.makeText((Activity)parent, "Invalid filename", Toast.LENGTH_SHORT)
					.show();
			return;
		} catch (IOException e) {
			Toast.makeText((Activity)parent, "Gcode file read error", Toast.LENGTH_SHORT)
					.show();
			return;
		}
		Log.d(TAG, "lines = " + numLines);
	}

	public boolean isActive() {
		return active;
	}

	private void cancel() {
		parent.stopMove();
		updateState(false);
	}

	private void queueFile() {
		String line;
		int idx = 0;

		if (gcodeFile == null)
			return;
		
		currentLinenum = 0;
		try {
			gcodeFile.seek(0);
			while ((line = gcodeFile.readLine()) != null) {
				idx++;
				String newstring = line.replaceFirst("^(/?)[nN](\\d{1,5})",
						"$1N" + idx);
				if (newstring.equals(line)) // No line number to start with,
											// so add one
					newstring = "N" + idx + " " + line;
				parent.sendGcode(newstring);
			}
		} catch (IOException e) {
			Log.e(TAG, "error reading file: " + e.getMessage());
		}
		
	}
}
