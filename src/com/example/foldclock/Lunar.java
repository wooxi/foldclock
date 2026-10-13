package com.example.foldclock;

import android.icu.util.Calendar;
import android.icu.util.ChineseCalendar;

/**
 * 农历。包装 android.icu.util.ChineseCalendar（API 24+，不需要任何权限）。
 * 注意：MONTH / DAY_OF_MONTH / IS_LEAP_MONTH 这几个常量定义在父类
 * android.icu.util.Calendar 上，通过子类名引用是合法的。
 */
public final class Lunar {

    private static final String[] MONTHS = {
            "正", "二", "三", "四", "五", "六", "七", "八", "九", "十", "冬", "腊"
    };

    private static final String[] DAYS = {
            "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"
    };

    private static final String[] ZODIAC = {
            "鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪"
    };

    private static final String[] GAN = {"甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"};
    private static final String[] ZHI = {"子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥"};

    private Lunar() {
    }

    /** 例：九月初四 / 闰六月初一；失败返回空串。 */
    public static String monthDay(long millis) {
        try {
            ChineseCalendar cc = new ChineseCalendar();
            cc.setTimeInMillis(millis);
            int m = cc.get(Calendar.MONTH);
            int d = cc.get(Calendar.DAY_OF_MONTH);
            boolean leap = cc.get(Calendar.IS_LEAP_MONTH) == 1;
            if (m < 0 || m >= MONTHS.length) return "";
            if (d < 1 || d > DAYS.length) return "";
            return (leap ? "闰" : "") + MONTHS[m] + "月" + DAYS[d - 1];
        } catch (Throwable t) {
            return "";
        }
    }

    /** 只要日，例：初四。日历格子用得上。 */
    public static String dayOnly(long millis) {
        try {
            ChineseCalendar cc = new ChineseCalendar();
            cc.setTimeInMillis(millis);
            int d = cc.get(Calendar.DAY_OF_MONTH);
            if (d < 1 || d > DAYS.length) return "";
            return DAYS[d - 1];
        } catch (Throwable t) {
            return "";
        }
    }

    /** 干支纪年 + 生肖，例：丙午马。 */
    public static String yearGanZhi(long millis) {
        try {
            ChineseCalendar cc = new ChineseCalendar();
            cc.setTimeInMillis(millis);
            int y = cc.get(Calendar.EXTENDED_YEAR);
            int g = ((y - 4) % 10 + 10) % 10;
            int z = ((y - 4) % 12 + 12) % 12;
            return GAN[g] + ZHI[z] + ZODIAC[z];
        } catch (Throwable t) {
            return "";
        }
    }
}
