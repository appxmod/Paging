package com.knziha.paging;

import android.database.Cursor;

public interface CursorReader {
	void ReadCursor(Cursor cursor, long rowID, long sortNum);
}
