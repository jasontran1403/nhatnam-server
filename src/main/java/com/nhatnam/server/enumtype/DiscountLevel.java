package com.nhatnam.server.enumtype;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum DiscountLevel {
    NONE(0),
    TEN(10),
    TWENTY(20),
    FREE(100);

    private final int percent;

    public static DiscountLevel fromPercent(int percent) {
        for (DiscountLevel d : values()) {
            if (d.percent == percent) return d;
        }
        throw new IllegalArgumentException("Invalid discount percent: " + percent);
    }
}