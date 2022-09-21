package com.knziha.paging;

import java.util.Arrays;
import java.util.Date;

public class MultiFieldSortCursorPage<T extends CursorReader> extends SimpleCursorPage<T>{
	long[] st_fds;
	long[] ed_fds;
	
	@Override
	public String toString() {
		return "MultiFieldSortCursorPage{" +
				"st_fd=" + Arrays.toString(st_fds) +
				", ed_fd=" + Arrays.toString(ed_fds) +
				", number_of_row=" + number_of_row +
				", rows=" + (rows!=null&&number_of_row>0?rows[0]+ " ~ " + rows[number_of_row-1]:rows) +
				'}';
	}
}
