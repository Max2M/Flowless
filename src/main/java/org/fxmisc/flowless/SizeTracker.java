package org.fxmisc.flowless;

import java.util.Optional;
import java.util.function.Function;

import javafx.beans.value.ObservableObjectValue;
import javafx.geometry.Bounds;
import javafx.scene.control.IndexRange;

import org.reactfx.Subscription;
import org.reactfx.collection.LiveList;
import org.reactfx.collection.MemoizationList;
import org.reactfx.value.Val;
import org.reactfx.value.ValBase;

final class SizeTracker {
    private final OrientationHelper orientation;
    private final ObservableObjectValue<Bounds> viewportBounds;
    private final MemoizationList<? extends Cell<?, ?>> cells;
    private final MemoizationList<Double> breadths;
    private final Val<Double> maxKnownMinBreadth;
    private final Val<Double> viewportBreadth;
    private final Val<Double> viewportLength;
    private final Val<Double> breadthForCells;
    private final MemoizationList<Double> lengths;
    private final Val<Double> averageLengthEstimate;
    private final Val<Double> totalLengthEstimate;
    private final Val<Double> lengthOffsetEstimate;

    private final Subscription subscription;

    public SizeTracker(
            OrientationHelper orientation,
            ObservableObjectValue<Bounds> viewportBounds,
            MemoizationList<? extends Cell<?, ?>> lazyCells) {
        this.orientation = orientation;
        this.viewportBounds = viewportBounds;
        this.cells = lazyCells;
        this.breadths = lazyCells.map(orientation::minBreadth).memoize();
        this.maxKnownMinBreadth = breadths.memoizedItems()
                .reduce(Math::max)
                .orElseConst(0.0);
        this.viewportBreadth = Val.map(viewportBounds, orientation::breadth);
        this.viewportLength = Val.map(viewportBounds, orientation::length);
        this.breadthForCells = Val.combine(
                maxKnownMinBreadth, viewportBreadth, Math::max);

        Val<Function<Cell<?, ?>, Double>> lengthFn = avoidFalseInvalidations(breadthForCells).map(
                breadth -> cell -> orientation.prefLength(cell, breadth));

        this.lengths = cells.mapDynamic(lengthFn).memoize();

        LiveList<Double> knownLengths = this.lengths.memoizedItems();
        Val<Double> sumOfKnownLengths = knownLengths.reduce((a, b) -> a + b).orElseConst(0.0);
        Val<Integer> knownLengthCount = knownLengths.sizeProperty();

        this.averageLengthEstimate = Val.create(
                () -> {
                    // make sure to use pref lengths of all present cells
                    for(int i = 0; i < cells.getMemoizedCount(); ++i) {
                        int j = cells.indexOfMemoizedItem(i);
                        lengths.force(j, j + 1);
                    }

                    int count = knownLengthCount.getValue();
                    return count == 0
                            ? null
                            : sumOfKnownLengths.getValue() / count;
                },
                sumOfKnownLengths, knownLengthCount);

        this.totalLengthEstimate = Val.combine(
                averageLengthEstimate, cells.sizeProperty(),
                (avg, n) -> n * avg);

        Val<Integer> firstVisibleIndex = Val.create(
                () -> cells.getMemoizedCount() == 0 ? null : cells.indexOfMemoizedItem(0),
                cells, cells.memoizedItems()); // need to observe cells.memoizedItems()
                // as well, because they may change without a change in cells.

        Val<? extends Cell<?, ?>> firstVisibleCell = cells.memoizedItems()
                .collapse(visCells -> visCells.isEmpty() ? null : visCells.get(0));

        Val<Integer> knownLengthCountBeforeFirstVisibleCell = Val.create(() -> {
            return firstVisibleIndex.getOpt()
                    .map(i -> lengths.getMemoizedCountBefore(Math.min(i, lengths.size())))
                    .orElse(0);
        }, lengths, firstVisibleIndex);

        Val<Double> totalKnownLengthBeforeFirstVisibleCell = knownLengths.reduceRange(
                knownLengthCountBeforeFirstVisibleCell.map(n -> new IndexRange(0, n)),
                (a, b) -> a + b).orElseConst(0.0);

        Val<Double> unknownLengthEstimateBeforeFirstVisibleCell = Val.combine(
                firstVisibleIndex,
                knownLengthCountBeforeFirstVisibleCell,
                averageLengthEstimate,
                (firstIdx, knownCnt, avgLen) -> (firstIdx - knownCnt) * avgLen);

        Val<Double> firstCellMinY = firstVisibleCell.flatMap(orientation::minYProperty);

        lengthOffsetEstimate = Val.combine(
                totalKnownLengthBeforeFirstVisibleCell,
                unknownLengthEstimateBeforeFirstVisibleCell,
                firstCellMinY,
                (a, b, minY) -> a + b - minY).orElseConst(0.0);

        // pinning totalLengthEstimate and lengthOffsetEstimate
        // binds it all together and enables memoization
        this.subscription = Subscription.multi(
                totalLengthEstimate.pin(),
                lengthOffsetEstimate.pin());
    }

    private static <T> Val<T> avoidFalseInvalidations(Val<T> src) {
        return new ValBase<T>() {
            @Override
            protected Subscription connect() {
                return src.observeChanges((obs, oldVal, newVal) -> invalidate());
            }

            @Override
            protected T computeValue() {
                return src.getValue();
            }
        };
    }

    public void dispose() {
        subscription.unsubscribe();
    }

    public Val<Double> maxCellBreadthProperty() {
        return maxKnownMinBreadth;
    }

    public Val<Double> viewportBreadthProperty() {
        return viewportBreadth;
    }

    public double getViewportBreadth() {
        return orientation.breadth(viewportBounds.get());
    }

    public Val<Double> viewportLengthProperty() {
        return viewportLength;
    }

    public double getViewportLength() {
        return orientation.length(viewportBounds.get());
    }

    public Val<Double> averageLengthEstimateProperty() {
        return averageLengthEstimate;
    }

    public Optional<Double> getAverageLengthEstimate() {
        return averageLengthEstimate.getOpt();
    }

    public Val<Double> totalLengthEstimateProperty() {
        return totalLengthEstimate;
    }

    public Val<Double> lengthOffsetEstimateProperty() {
        return lengthOffsetEstimate;
    }

    public double breadthFor(int itemIndex) {
        assert cells.isMemoized(itemIndex);
        breadths.force(itemIndex, itemIndex + 1);
        return breadthForCells.getValue();
    }

    public void forgetSizeOf(int itemIndex) {
        breadths.forget(itemIndex, itemIndex + 1);
        lengths.forget(itemIndex, itemIndex + 1);
    }

    public double lengthFor(int itemIndex) {
        return lengths.get(itemIndex);
    }

    public double getCellLayoutBreadth() {
        return breadthForCells.getValue();
    }
}