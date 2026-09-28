package com.vvcs.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Exact rational number, used for every frame rate and timebase in VVCS.
 *
 * <p>Frame rates are never stored as floating point. Conversions between timebases
 * go through {@link #multiply(long)}, {@link #divide(long)} and {@link #scaleTo(long)},
 * which work on exact {@link BigInteger} intermediates and only round at the very end.
 */
public record Rational(long numerator, long denominator) implements Comparable<Rational> {

    public static final Rational ZERO = new Rational(0, 1);
    public static final Rational ONE = new Rational(1, 1);

    public Rational {
        if (denominator == 0) {
            throw new IllegalArgumentException("denominator must not be zero");
        }
        if (numerator == 0) {
            denominator = 1;
        } else if (denominator < 0) {
            numerator = -numerator;
            denominator = -denominator;
        }
        long divisor = gcd(Math.abs(numerator), denominator);
        numerator /= divisor;
        denominator /= divisor;
    }

    public static Rational of(long numerator, long denominator) {
        return new Rational(numerator, denominator);
    }

    public static Rational of(BigDecimal value) {
        if (value.scale() <= 0 && value.stripTrailingZeros().scale() <= 0) {
            return new Rational(value.toBigIntegerExact().longValueExact(), 1);
        }
        BigInteger stripped = value.toBigInteger();
        return new Rational(stripped.longValueExact(), value.scale());
    }

    @JsonIgnore
    public double toDouble() {
        return (double) numerator / (double) denominator;
    }

    @JsonIgnore
    public boolean isInteger() {
        return denominator == 1;
    }

    public Rational multiply(long factor) {
        return new Rational(Math.multiplyExact(numerator, factor), denominator);
    }

    public Rational multiply(Rational other) {
        return new Rational(
                BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(other.numerator)).longValueExact(),
                BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(other.denominator)).longValueExact());
    }

    public Rational divide(long divisor) {
        return new Rational(numerator, Math.multiplyExact(denominator, divisor));
    }

    /**
     * Maps a frame count counted in this timebase onto {@code target} and rounds to the
     * nearest whole frame, halves away from zero. This is the single conversion point
     * between the frame numbering of a source asset and of a project.
     */
    public long scaleTo(long frames, Rational target) {
        BigInteger scaled = BigInteger.valueOf(frames)
                .multiply(BigInteger.valueOf(target.numerator))
                .multiply(BigInteger.valueOf(denominator));
        BigInteger divisor = BigInteger.valueOf(target.denominator)
                .multiply(BigInteger.valueOf(numerator));
        return scaled.signum() < 0
                ? scaled.subtract(divisor.shiftRight(1)).divide(divisor).longValueExact()
                : scaled.add(divisor.shiftRight(1)).divide(divisor).longValueExact();
    }

    @JsonIgnore
    public BigDecimal toBigDecimal(int scale) {
        return BigDecimal.valueOf(numerator)
                .divide(BigDecimal.valueOf(denominator), scale, RoundingMode.HALF_UP);
    }

    public String toJson() {
        return numerator + "/" + denominator;
    }

    @Override
    public int compareTo(Rational other) {
        return BigInteger.valueOf(numerator)
                .multiply(BigInteger.valueOf(other.denominator))
                .compareTo(BigInteger.valueOf(other.numerator).multiply(BigInteger.valueOf(denominator)));
    }

    @Override
    public String toString() {
        return toJson();
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = a % b;
            a = b;
            b = t;
        }
        return a;
    }
}
