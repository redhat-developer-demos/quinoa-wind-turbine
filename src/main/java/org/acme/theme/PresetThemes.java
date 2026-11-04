package org.acme.theme;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

import org.acme.theme.RaceTheme.ThemeTeam;
import org.acme.theme.RaceTheme.Track;
import org.acme.theme.RaceTheme.Track.ViewBox;

/**
 * Built-in themes an admin can apply without an LLM. Each is already valid and keeps two teams with
 * contrasting colors. A team's color paints the F1 car body and its optional accent paints the livery
 * stripes and wing elements (null keeps them white). A null track leaves the current track in place;
 * the classic preset sets a track.
 */
@ApplicationScoped
public class PresetThemes {

    // The hand-painted parkland track with its two hand-drawn lane paths.
    private static final Track PARKLAND = new Track("race-track-1.png", new ViewBox(777, 518), null, null, List.of(
        "M 745 243 Q 750 340 750 420 Q 695 515 560 470 Q 490 350 420 360 Q 350 350 310 420 Q 240 510 180 450 "
            + "L 80 270 Q 70 200 120 150 L 270 90 Q 310 70 350 90 L 410 110 Q 470 110 510 80 L 570 40 Q 600 20 640 20 "
            + "Q 670 30 700 50 Q 720 80 720 110 Q 740 180 750 320 ",
        "M 720 230 Q 735 335 720 430 Q 670 490 570 440 Q 505 335 420 330 Q 330 330 290 410 Q 240 490 190 415 "
            + "L 105 260 Q 90 195 150 160 L 280 110 Q 310 90 350 110 L 420 130 Q 480 130 530 100 L 590 60 Q 610 40 640 40 "
            + "Q 690 80 705 115 Q 720 180 720 320 "));

    // The painted Swiss Alps track. It has the same 3:2 shape as the parkland, and its two lane paths
    // follow the road in the same 777x518 space, shifted left on the start straight to sit on the grid.
    private static final Track SWISS_ALPS = new Track("swiss-alps-track.png",
        new ViewBox(777, 518), null, null, List.of(
        "M 733 243 Q 738 340 738 420 Q 695 515 560 470 Q 490 350 420 360 Q 350 350 310 420 Q 240 510 180 450 "
            + "L 80 270 Q 70 200 120 150 L 270 90 Q 310 70 350 90 L 410 110 Q 470 110 510 80 L 570 40 Q 600 20 640 20 "
            + "Q 670 30 700 50 Q 720 80 720 110 Q 728 180 738 320 ",
        "M 710 230 Q 725 335 710 430 Q 670 490 570 440 Q 505 335 420 330 Q 330 330 290 410 Q 240 490 190 415 "
            + "L 105 260 Q 90 195 150 160 L 280 110 Q 310 90 350 110 L 420 130 Q 480 130 530 100 L 590 60 Q 610 40 640 40 "
            + "Q 690 80 705 115 Q 710 180 710 320 "));

    // The painted Atlanta track: the park loop around Centennial Park, the Georgia Aquarium and the
    // World of Coca-Cola. The render keeps the parkland loop, so it reuses the same two lane paths.
    private static final Track ATLANTA = new Track("atlanta-track.jpeg",
        new ViewBox(777, 518), null, null, PARKLAND.lanes());

    // The painted KubeCon + CloudNativeCon North America 2026 track in Salt Lake City: the loop past
    // the Salt Palace, around Temple Square and back to the START/FINISH gantry. Both lane paths are
    // traced from the painting, run down the start straight and close on themselves, so a finished
    // car stops exactly on the line instead of short of it or past it.
    private static final Track KUBECON_SLC = new Track("kubcon-salt-lake-city-race-track-2026-winter.png",
        new ViewBox(777, 518), null, null, List.of(
        "M 725 290 C 725 295 725 311 725 322 C 726 333 726 344 726 355 C 726 366 727 377 727 387 " +
        "C 726 398 727 410 723 420 C 720 429 713 439 705 446 C 697 452 686 455 675 459 " +
        "C 665 462 654 463 643 464 C 632 465 621 465 611 463 C 600 461 589 458 581 452 " +
        "C 572 446 564 437 558 429 C 551 421 546 411 540 402 C 534 393 528 383 521 375 " +
        "C 514 367 506 359 497 353 C 488 347 478 343 468 340 C 458 336 447 335 436 333 " +
        "C 425 332 414 332 403 333 C 393 334 381 334 371 337 C 361 339 350 344 341 350 " +
        "C 333 355 325 364 317 371 C 310 379 304 388 297 396 C 290 405 283 414 276 421 " +
        "C 268 429 260 438 250 441 C 241 445 228 447 219 444 C 209 441 200 432 194 424 " +
        "C 187 416 184 405 178 396 C 173 386 168 377 162 368 C 156 358 150 349 144 340 " +
        "C 138 331 132 322 126 313 C 121 304 116 294 112 284 C 108 274 106 263 105 252 " +
        "C 104 242 104 230 106 220 C 108 210 113 199 118 190 C 124 181 131 172 139 165 " +
        "C 147 159 158 155 168 151 C 178 147 190 146 200 143 C 210 139 220 134 230 130 " +
        "C 240 126 249 121 259 117 C 270 113 280 108 290 106 C 301 104 312 102 322 103 " +
        "C 333 104 343 108 354 111 C 364 114 375 118 385 121 C 396 123 406 126 417 126 " +
        "C 428 127 439 127 450 126 C 460 124 471 122 481 119 C 492 116 502 112 512 107 " +
        "C 521 102 531 97 540 91 C 549 85 557 78 566 72 C 575 65 584 58 593 54 C 603 49 613 44 624 43 " +
        "C 634 42 646 43 656 48 C 665 52 674 60 680 68 C 687 77 690 87 694 97 C 698 107 701 118 704 129 " +
        "C 707 139 710 150 712 160 C 714 171 715 182 716 192 C 718 203 719 214 720 225 " +
        "C 721 236 722 246 723 257 C 723 268 724 284 725 290  ",
        "M 747 289 C 747 295 747 312 747 324 C 748 336 748 347 748 359 C 748 370 749 382 749 394 " +
        "C 748 405 748 418 744 428 C 740 439 733 449 725 457 C 717 465 706 471 696 476 " +
        "C 685 480 673 481 662 483 C 650 485 638 487 627 487 C 615 486 603 485 592 482 " +
        "C 582 478 571 472 562 465 C 553 458 545 449 538 440 C 531 431 526 420 519 410 " +
        "C 513 401 507 390 498 382 C 490 375 480 368 469 363 C 459 359 447 357 435 355 " +
        "C 424 354 412 354 401 355 C 389 356 377 357 366 361 C 356 365 347 374 338 381 " +
        "C 330 389 323 399 316 408 C 308 417 301 426 293 435 C 285 443 277 452 267 457 " +
        "C 257 463 245 468 234 468 C 223 469 210 465 200 460 C 191 455 182 445 175 436 " +
        "C 168 427 164 416 159 406 C 153 395 147 385 141 375 C 135 366 128 356 122 346 " +
        "C 115 336 109 327 103 317 C 98 306 93 296 89 285 C 86 274 83 262 82 251 C 82 239 82 227 84 216 " +
        "C 87 205 91 194 97 184 C 102 173 109 163 117 155 C 125 147 136 141 146 136 C 156 131 168 129 179 125 " +
        "C 190 122 202 119 212 114 C 223 110 233 104 244 99 C 255 95 266 90 277 87 C 288 84 300 81 311 81 " +
        "C 322 80 334 83 345 86 C 357 88 368 93 379 96 C 390 99 401 103 413 104 C 424 105 436 105 448 104 " +
        "C 459 102 471 100 482 96 C 493 92 503 87 513 81 C 523 76 533 69 542 62 C 552 55 561 47 571 41 " +
        "C 581 35 591 29 602 26 C 613 22 625 20 636 20 C 647 21 660 25 670 30 C 679 35 689 43 696 52 " +
        "C 703 61 708 72 713 83 C 717 94 720 105 723 116 C 727 127 730 139 732 150 C 734 161 736 173 738 185 " +
        "C 739 196 740 208 742 219 C 743 231 744 242 744 254 C 745 266 746 283 747 289  "));

    public List<RaceTheme> all() {
        return List.of(
            new RaceTheme("classic", "Mountains vs Beach", List.of(
                new ThemeTeam(1, "Mountains", "#9e9e9e", "car-grey", null),
                new ThemeTeam(2, "Beach", "#29b6f6", "car-blue-convertible", null)), PARKLAND),
           new RaceTheme("ferrari-mercedes", "Ferrari vs Mercedes", List.of(
               new ThemeTeam(1, "Ferrari", "#dc0000", "car-red", "#ffffff", "ibm"),
               new ThemeTeam(2, "Mercedes", "#c0c7cc", "car-grey", "#00d2be")), SWISS_ALPS),
           new RaceTheme("atlanta", "Aquarium vs Coca-Cola", List.of(
               new ThemeTeam(1, "Aquarium", "#29b6f6", "car-blue-convertible", "#ffffff"),
               new ThemeTeam(2, "Coca-Cola", "#dc0000", "car-red", "#ffffff")), ATLANTA),
           new RaceTheme("kubecon-slc", "KubeCon Salt Lake City", List.of(
               new ThemeTeam(1, "Kubernetes", "#326ce5", "car-blue-convertible", "#ffffff"),
               new ThemeTeam(2, "CNCF", "#f57c00", "car-classic-black", "#ffffff")), KUBECON_SLC),
           new RaceTheme("forest-sun", "Forest vs Sun", List.of(
               new ThemeTeam(1, "Forest", "#2e7d32", "car-grey", "#fdd835"),
               new ThemeTeam(2, "Sunshine", "#fdd835", "car-orange", "#2e7d32")), null),
           new RaceTheme("royal-teal", "Royal vs Teal", List.of(
               new ThemeTeam(1, "Royal", "#6a1b9a", "car-barbie", "#ffd54f"),
               new ThemeTeam(2, "Lagoon", "#00acc1", "car-blue-convertible", null)), null),
           new RaceTheme("silver-arrows", "Silver vs Black", List.of(
               new ThemeTeam(1, "Silver", "#cfd8dc", "car-grey", "#00bfa5"),
               new ThemeTeam(2, "Shadow", "#212121", "car-classic-black", "#e53935")), null),
            new RaceTheme("fire-ice", "Fire vs Ice", List.of(
                new ThemeTeam(1, "Blaze", "#e53935", "car-red", null),
                new ThemeTeam(2, "Frost", "#42a5f5", "car-blue-convertible", null)), null),
            new RaceTheme("pink-black", "Pink vs Black", List.of(
                new ThemeTeam(1, "Hot Pink", "#ec407a", "car-barbie", null),
                new ThemeTeam(2, "Midnight", "#424242", "car-classic-black", null)), null),
            new RaceTheme("citrus", "Orange vs Lime", List.of(
                new ThemeTeam(1, "Tangerine", "#fb8c00", "car-orange", null),
                new ThemeTeam(2, "Lime", "#7cb342", "car-grey", null)), null));
    }
}
