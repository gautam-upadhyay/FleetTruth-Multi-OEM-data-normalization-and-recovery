package io.fleettruth.domain;

public final class VinValidator {

  private VinValidator() {}

  public static boolean valid(String vin) {
    if (vin == null || !vin.matches("[A-HJ-NPR-Z0-9]{17}")) return false;
    int[] weights = { 8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2 };
    int sum = 0;
    for (int i = 0; i < 17; i++) {
      char c = vin.charAt(i);
      int value = Character.isDigit(c)
        ? c - '0'
        : switch (c) {
            case 'A', 'J' -> 1;
            case 'B', 'K', 'S' -> 2;
            case 'C', 'L', 'T' -> 3;
            case 'D', 'M', 'U' -> 4;
            case 'E', 'N', 'V' -> 5;
            case 'F', 'W' -> 6;
            case 'G', 'P', 'X' -> 7;
            case 'H', 'Y' -> 8;
            case 'R', 'Z' -> 9;
            default -> -1;
          };
      if (value < 0) return false;
      sum += value * weights[i];
    }
    int expected = sum % 11;
    return vin.charAt(8) == (expected == 10 ? 'X' : (char) ('0' + expected));
  }
}
