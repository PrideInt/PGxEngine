package pgx.client.ui;

import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.text.Font;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads the bundled typefaces and the stylesheet.
 *
 * The fonts are bundled rather than named and hoped for: Courier Prime is not
 * installed anywhere by default, and Cascadia Mono only sometimes is. Both are
 * SIL OFL 1.1 — see resources/fonts/NOTICE.txt.
 *
 * Nothing here throws. If a font is missing the UI falls back to the platform
 * monospace and stays usable, which is worth more than a clean failure.
 */
public final class Theme {

    private static final String STYLESHEET = "/pgx.css";

    private static final String[] FONTS = {
            "/fonts/CascadiaMono-Regular.ttf",
            "/fonts/CascadiaMono-Bold.ttf",
            "/fonts/CourierPrime-Regular.ttf",
            "/fonts/CourierPrime-Bold.ttf",
            "/fonts/CourierPrime-Italic.ttf"
    };

    private static final String LOGO = "/brand/logo.png";

    /** Window and taskbar icon. Several sizes: the OS picks the nearest and
     *  scales badly from a single large one. */
    private static final String[] ICONS = {
            "/brand/icon-16.png",
            "/brand/icon-32.png",
            "/brand/icon-48.png",
            "/brand/icon-64.png",
            "/brand/icon-128.png",
            "/brand/icon-256.png"
    };

    private static boolean fontsLoaded;

    private Theme() {
    }

    /** Every icon size that loaded. Empty means JavaFX keeps its default. */
    public static List<Image> appIcons() {
        List<Image> icons = new ArrayList<>();

        for (String path : ICONS) {
            try (InputStream in = Theme.class.getResourceAsStream(path)) {
                if (in == null) {
                    continue;
                }
                Image image = new Image(in);
                if (!image.isError()) {
                    icons.add(image);
                }
            } catch (Exception e) {
                // A missing or unreadable size is not worth failing over.
            }
        }
        return icons;
    }

    /**
     * An institutional mark, if one has been placed in resources/brand/.
     *
     * Returns null when there is none, which is the default: the app ships
     * without a logo and the header lays out without it. Supplying one is a
     * decision for whoever builds it, and carries a claim — that the body on
     * the mark is content to be associated with an unvalidated educational
     * tool. The attribution line in the status bar states authorship without
     * making that claim, which is usually what is actually wanted.
     */
    public static ImageView logo(double height) {
        try (InputStream in = Theme.class.getResourceAsStream(LOGO)) {
            if (in == null) {
                return null;
            }
            Image image = new Image(in);
            if (image.isError()) {
                System.err.println("brand/logo.png could not be decoded; ignoring it.");
                return null;
            }
            ImageView view = new ImageView(image);
            view.setPreserveRatio(true);
            view.setFitHeight(height);
            view.setSmooth(true);
            return view;
        } catch (Exception e) {
            return null;
        }
    }

    public static void apply(Scene scene) {
        loadFonts();

        URL sheet = Theme.class.getResource(STYLESHEET);
        if (sheet == null) {
            System.err.println("pgx.css is not on the classpath; using JavaFX defaults.");
            return;
        }
        scene.getStylesheets().add(sheet.toExternalForm());
    }

    private static synchronized void loadFonts() {
        if (fontsLoaded) {
            return;
        }
        fontsLoaded = true;

        for (String path : FONTS) {
            try (InputStream in = Theme.class.getResourceAsStream(path)) {
                if (in == null) {
                    System.err.println("Font not on the classpath: " + path);
                } else if (Font.loadFont(in, 12) == null) {
                    System.err.println("Font rejected by JavaFX: " + path);
                }
            } catch (Exception e) {
                System.err.println("Font failed to load: " + path + " — " + e.getMessage());
            }
        }
    }
}
