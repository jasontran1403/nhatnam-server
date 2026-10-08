package com.nhatnam.server.util;

/**
 * Chuyển số tiền (long) sang chữ Tiếng Việt.
 * Dùng để điền trường totalAmountWithTaxInWords trong hóa đơn điện tử.
 *
 * VD: 125000 → "Một trăm hai mươi lăm nghìn đồng"
 */
public class NumberToWordsVN {

    private static final String[] DON_VI = {
        "", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"
    };

    private static final String[] HANG = {
        "", "nghìn", "triệu", "tỷ"
    };

    public static String convert(long amount) {
        if (amount == 0) return "Không đồng";
        if (amount < 0)  return "Âm " + convert(-amount);

        String result = readNumber(amount).trim();
        // Viết hoa chữ đầu
        result = Character.toUpperCase(result.charAt(0)) + result.substring(1);
        return result + " đồng";
    }

    private static String readNumber(long n) {
        if (n == 0) return "";

        String result = "";
        int groupIndex = 0;

        while (n > 0) {
            int group = (int)(n % 1000);
            if (group != 0) {
                String groupStr = readThreeDigits(group, groupIndex > 0);
                if (groupIndex > 0) {
                    groupStr = groupStr + " " + HANG[groupIndex];
                }
                result = groupStr + (result.isEmpty() ? "" : " " + result);
            }
            n /= 1000;
            groupIndex++;
        }

        return result.trim();
    }

    private static String readThreeDigits(int n, boolean hasHigherGroup) {
        int tram  = n / 100;
        int chuc  = (n % 100) / 10;
        int donvi = n % 10;

        StringBuilder sb = new StringBuilder();

        if (tram > 0) {
            sb.append(DON_VI[tram]).append(" trăm");
        } else if (hasHigherGroup && n < 100) {
            sb.append("không trăm");
        }

        if (chuc > 0) {
            if (sb.length() > 0) sb.append(" ");
            if (chuc == 1) {
                sb.append("mười");
            } else {
                sb.append(DON_VI[chuc]).append(" mươi");
            }
            if (donvi > 0) {
                sb.append(" ");
                if (donvi == 1 && chuc > 1)       sb.append("mốt");
                else if (donvi == 5 && chuc >= 1) sb.append("lăm");
                else                               sb.append(DON_VI[donvi]);
            }
        } else if (donvi > 0) {
            if (sb.length() > 0) sb.append(" lẻ ");
            sb.append(DON_VI[donvi]);
        }

        return sb.toString();
    }

    // Quick test
    public static void main(String[] args) {
        System.out.println(convert(0));
        System.out.println(convert(5000));
        System.out.println(convert(10000));
        System.out.println(convert(125000));
        System.out.println(convert(1250000));
        System.out.println(convert(1000000000L));
        System.out.println(convert(123456789L));
    }
}
