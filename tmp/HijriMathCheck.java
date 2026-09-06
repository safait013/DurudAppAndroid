import com.darood.app.HijriMath;
import java.util.Calendar;
import java.util.TimeZone;

public class HijriMathCheck {
    public static void main(String[] args) {
        // 1 Muharram 1445 == 19 July 2023 (widely published)
        int[] h1 = HijriMath.hijriFromGregorian(2023, 7, 19);
        System.out.println("2023-07-19 -> " + java.util.Arrays.toString(h1) + " (expect ~[1,1,1445])");

        int[] h2 = HijriMath.hijriFromGregorian(2026, 9, 15);
        System.out.println("2026-09-15 -> " + java.util.Arrays.toString(h2));

        int[] h3 = HijriMath.hijriFromGregorian(2026, 3, 11); // Ramadan 1447 start ~ Feb 2026? print
        System.out.println("2026-03-11 -> " + java.util.Arrays.toString(h3));

        // Roundtrip via julian day
        int[] rt = HijriMath.hijriFromGregorian(2026, 9, 15);
        System.out.println("hijri(2026,9,15)=" + java.util.Arrays.toString(rt));

        // Sunset approximations (2026-09-04, month index 8)
        long dhaka = HijriMath.sunsetApproxMillis(23.8103, 90.4125, 2026, 8, 4);
        System.out.println("Dhaka sunset 2026-09-04 device-local: " + HijriMath.hhmm(dhaka) + " (expect ~18:10 GMT+6 => local 18:10, here tz=" + TimeZone.getDefault().getID() + ")");
        long london = HijriMath.sunsetApproxMillis(51.5074, -0.1278, 2026, 8, 4);
        System.out.println("London sunset 2026-09-04: " + HijriMath.hhmm(london) + " (expect ~19:35 BST)");

        // Sunset rule checks
        Calendar cal = Calendar.getInstance();
        TimeZone utc = TimeZone.getTimeZone("UTC");
        cal.setTimeZone(utc);
        cal.clear();
        cal.set(2026, 8, 4, 12, 0, 0); // midday 12:00 UTC = 18:00 Dhaka
        long noonUtc = cal.getTimeInMillis();
        System.out.println("Before/after sunset 2026-09-04 18:10 Dhaka: noonDt=" + (noonUtc < dhaka) + " (expect true)");
        cal.set(2026, 8, 4, 13, 0, 0); // 19:00 Dhaka - after sunset
        long eveUtc = cal.getTimeInMillis();
        System.out.println("Evening after sunset (expect false): " + (eveUtc < dhaka));
    }
}