package com.hedgeguard.risk_intraday;

import com.hedgeguard.bridge.FundView;
import com.hedgeguard.bridge.MirrorSnapshot;
import com.hedgeguard.bridge.PositionView;
import com.hedgeguard.bridge.ReplayProgress;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 测试用镜像快照工厂（不落库、不查 C++）。 */
final class TestSnapshots {

    private TestSnapshots() {
    }

    static PositionView position(int subAccountId, String instrumentId, String positionQty,
                                 String availableQty, String floatPnl, String margin) {
        PositionView p = new PositionView();
        p.subAccountId = subAccountId;
        p.instrumentId = instrumentId;
        p.productId = 100L + subAccountId;
        p.positionQty = positionQty;
        p.availableQty = availableQty;
        p.avgPrice = "1000";
        p.floatPnl = floatPnl;
        p.marginOccupied = margin;
        return p;
    }

    static FundView fund(int subAccountId, String balance, String available, String margin,
                         String riskRatio) {
        FundView f = new FundView();
        f.subAccountId = subAccountId;
        f.balance = balance;
        f.available = available;
        f.marginOccupied = margin;
        f.riskRatio = riskRatio;
        return f;
    }

    static MirrorSnapshot snapshot(String takenAt, long seq, PositionView... positions) {
        return new MirrorSnapshot(ReplayProgress.State.READY, seq, 1L, takenAt,
                Arrays.asList(positions), new ArrayList<>());
    }

    static MirrorSnapshot snapshot(String takenAt, long seq, List<PositionView> positions,
                                   List<FundView> funds) {
        return new MirrorSnapshot(ReplayProgress.State.READY, seq, 1L, takenAt, positions, funds);
    }

    static List<PositionView> list(PositionView... positions) {
        return new ArrayList<>(Arrays.asList(positions));
    }

    static List<FundView> funds(FundView... funds) {
        return new ArrayList<>(Arrays.asList(funds));
    }
}
