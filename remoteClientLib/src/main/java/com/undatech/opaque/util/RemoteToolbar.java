package com.undatech.opaque.util;

import android.content.Context;
import android.support.annotation.Nullable;
import android.support.v7.widget.Toolbar;
import android.util.AttributeSet;

public class RemoteToolbar extends Toolbar {
    private static final String TAG = "RemoteToolbar";

    public RemoteToolbar(Context context) {
        super(context);
    }

    public RemoteToolbar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public RemoteToolbar(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public void setPositionToMakeVisible(
            int XCoor, int YCoor, int rootRight, int rootBottom, int standardPositionX, int standardPositionY
    ) {
        if (XCoor > rootRight || YCoor > rootBottom) {
            this.setX(standardPositionX);
            this.setY(standardPositionY);
        } else {
            this.setX(XCoor);
            this.setY(YCoor);
        }
    }
}
