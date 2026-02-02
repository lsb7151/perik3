package com.coremotion.perik3.datasource

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

data class FakeBlePacket(
    val t1StateId: Int,
    val positionX: Float,
    val positionY: Float,
    val positionZ: Float,
    val peakResponseSlope: Float,
    val directionVectorX: Float,
    val directionVectorY: Float,
    val bipolarNormalizedValue: Float
)

class FakeBleDataSource {

    private var tickSeconds: Float = 0f

    //  위치(포인트) 5초마다 변경
    private var lastPositionChangeTimeSeconds: Float = 0f
    private var cachedPositionTriple: Triple<Float, Float, Float> = Triple(0f, -1f, 1f)

    //  옆 그래프/상태(2초마다 변경)
    private var lastSideUiChangeTimeSeconds: Float = 0f
    private var cachedT1StateId: Int = 0
    private var cachedPeakResponseSlope: Float = 0f
    private var cachedBipolarNormalizedValue: Float = 0f

    fun nextPacket(tabIndex: Int): FakeBlePacket {
        // MeasurementFragment에서 80ms delay 기준
        tickSeconds += 0.08f

        // 방향 벡터는 부드럽게 계속 변화(원하면 이것도 2초 캐시로 묶을 수 있음)
        val directionVectorX = cos(tickSeconds).toFloat()
        val directionVectorY = sin(tickSeconds).toFloat()

        //  1초마다만 상태/그래프 값 변경
        val sideUiChangeIntervalSeconds = 1.0f
        if ((tickSeconds - lastSideUiChangeTimeSeconds) >= sideUiChangeIntervalSeconds) {
            lastSideUiChangeTimeSeconds = tickSeconds

            cachedT1StateId = Random.nextInt(0, 3)             // 0/1/2
            cachedPeakResponseSlope = Random.nextFloat() * 6.0f // 임시 스케일(0~6)
            cachedBipolarNormalizedValue = (Random.nextFloat() * 2f - 1f).coerceIn(-1f, 1f) // -1~+1
        }

        // 3초마다만 위치 변경
        val positionChangeIntervalSeconds = 3.0f
        if ((tickSeconds - lastPositionChangeTimeSeconds) >= positionChangeIntervalSeconds) {
            lastPositionChangeTimeSeconds = tickSeconds
            cachedPositionTriple = createNewPositionTriple(tabIndex)
        }

        val (x, y, z) = cachedPositionTriple

        return FakeBlePacket(
            t1StateId = cachedT1StateId,
            positionX = x,
            positionY = y,
            positionZ = z,
            peakResponseSlope = cachedPeakResponseSlope,
            directionVectorX = directionVectorX,
            directionVectorY = directionVectorY,
            bipolarNormalizedValue = cachedBipolarNormalizedValue
        )
    }

    private fun createNewPositionTriple(tabIndex: Int): Triple<Float, Float, Float> {
        return when (tabIndex) {
            1 -> { // T2: 5개 패턴
                when (Random.nextInt(0, 5)) {
                    0 -> Triple(0f, -1f,  1f)    // (0,-,+)
                    1 -> Triple(-1f, -1f, -1f)   // (-,-,-)
                    2 -> Triple( 1f, -1f, -1f)   // (+,-,-)
                    3 -> Triple(-1f,  1f, -1f)   // (-,+,-)
                    else -> Triple( 1f,  1f, -1f) // (+,+,-)
                }
            }
            else -> { // T3: 마지막 2개 배제 → 3개만
                when (Random.nextInt(0, 3)) {
                    0 -> Triple(0f, -1f,  1f)    // (0,-,+)
                    1 -> Triple(-1f, -1f, -1f)   // (-,-,-)
                    else -> Triple( 1f, -1f, -1f) // (+,-,-)
                }
            }
        }
    }
}