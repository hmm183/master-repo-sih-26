package nisargpatel.deadreckoning.fusion.math

import org.ejml.data.DenseMatrix64F
import org.ejml.factory.LinearSolverFactory
import org.ejml.ops.CommonOps
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * High-performance numerical linear algebra and unscented transform utilities
 * for dead-reckoning filters, backed by EJML (Efficient Java Matrix Library).
 */
object MatrixMath {

    const val TWO_PI = 2.0 * PI

    /**
     * Normalizes an angle into [-PI, PI).
     */
    fun normalizeRadians(radians: Double): Double {
        var a = radians % TWO_PI
        if (a >= PI) a -= TWO_PI
        if (a < -PI) a += TWO_PI
        return a
    }

    /**
     * Shortest angular difference (target - source) in [-PI, PI).
     */
    fun shortestAngleDelta(sourceRad: Double, targetRad: Double): Double {
        return normalizeRadians(targetRad - sourceRad)
    }

    /**
     * Wraps a 2D double array into an EJML DenseMatrix64F.
     */
    fun dense(rows: Int, cols: Int, data: DoubleArray): DenseMatrix64F {
        return DenseMatrix64F(rows, cols, true, *data)
    }

    /**
     * Identity matrix of size [dim].
     */
    fun identity(dim: Int): DenseMatrix64F {
        return CommonOps.identity(dim)
    }

    /**
     * Adds two matrices: A + B.
     */
    fun add(a: DenseMatrix64F, b: DenseMatrix64F): DenseMatrix64F {
        val out = DenseMatrix64F(a.numRows, a.numCols)
        CommonOps.add(a, b, out)
        return out
    }

    /**
     * Subtracts two matrices: A - B.
     */
    fun subtract(a: DenseMatrix64F, b: DenseMatrix64F): DenseMatrix64F {
        val out = DenseMatrix64F(a.numRows, a.numCols)
        CommonOps.subtract(a, b, out)
        return out
    }

    /**
     * Matrix multiplication: A * B.
     */
    fun mult(a: DenseMatrix64F, b: DenseMatrix64F): DenseMatrix64F {
        val out = DenseMatrix64F(a.numRows, b.numCols)
        CommonOps.mult(a, b, out)
        return out
    }

    /**
     * Matrix transpose: A^T.
     */
    fun transpose(a: DenseMatrix64F): DenseMatrix64F {
        val out = DenseMatrix64F(a.numCols, a.numRows)
        CommonOps.transpose(a, out)
        return out
    }

    /**
     * Invert a square matrix. If singular, adds jitter diagonal to ensure stability.
     */
    fun invert(a: DenseMatrix64F): DenseMatrix64F {
        val out = DenseMatrix64F(a.numRows, a.numCols)
        val success = CommonOps.invert(a, out)
        if (!success) {
            // Fallback: regularize with small diagonal ridge epsilon
            val reg = a.copy()
            for (i in 0 until reg.numRows) {
                reg.set(i, i, reg.get(i, i) + 1e-6)
            }
            CommonOps.invert(reg, out)
        }
        return out
    }

    /**
     * Robust Cholesky decomposition L such that L * L^T = P.
     * If P has near-zero or slightly negative eigenvalues due to numerical precision,
     * it progressively adds jitter to the diagonal until positive-definiteness is restored.
     */
    fun choleskyLower(p: DenseMatrix64F): DenseMatrix64F {
        val n = p.numRows
        val l = DenseMatrix64F(n, n)
        val copy = p.copy()

        var jitter = 0.0
        var attempts = 0
        while (attempts < 5) {
            if (jitter > 0.0) {
                for (i in 0 until n) {
                    copy.set(i, i, copy.get(i, i) + jitter)
                }
            }

            var success = true
            for (i in 0 until n) {
                for (j in 0..i) {
                    var sum = copy.get(i, j)
                    for (k in 0 until j) {
                        sum -= l.get(i, k) * l.get(j, k)
                    }
                    if (i == j) {
                        if (sum <= 1e-12) {
                            success = false
                            break
                        }
                        l.set(i, j, sqrt(sum))
                    } else {
                        val diag = l.get(j, j)
                        if (diag <= 1e-12) {
                            success = false
                            break
                        }
                        l.set(i, j, sum / diag)
                    }
                }
                if (!success) break
            }

            if (success) return l

            // Reset l and bump jitter
            CommonOps.fill(l, 0.0)
            jitter = if (jitter == 0.0) 1e-6 else jitter * 10.0
            attempts++
        }

        // Diagonal fallback
        for (i in 0 until n) {
            val v = copy.get(i, i).coerceAtLeast(1e-6)
            l.set(i, i, sqrt(v))
        }
        return l
    }

    /**
     * Solves linear system A * x = b.
     */
    fun solve(a: DenseMatrix64F, b: DenseMatrix64F): DenseMatrix64F {
        val x = DenseMatrix64F(b.numRows, b.numCols)
        val solver = LinearSolverFactory.linear(a.numRows)
        if (!solver.setA(a)) {
            // Regularize
            val regA = a.copy()
            for (i in 0 until regA.numRows) {
                regA.set(i, i, regA.get(i, i) + 1e-6)
            }
            solver.setA(regA)
        }
        solver.solve(b, x)
        return x
    }

    /**
     * Unscented Transform parameters & sigma-point generation.
     */
    class UnscentedTransform(
        val dim: Int,
        val alpha: Double = 1e-3,
        val beta: Double = 2.0,
        val kappa: Double = 0.0
    ) {
        val numSigmaPoints: Int = 2 * dim + 1
        val lambda: Double = alpha * alpha * (dim + kappa) - dim
        val gamma: Double = sqrt(dim + lambda)

        val wm: DoubleArray = DoubleArray(numSigmaPoints)
        val wc: DoubleArray = DoubleArray(numSigmaPoints)

        init {
            wm[0] = lambda / (dim + lambda)
            wc[0] = wm[0] + (1.0 - alpha * alpha + beta)
            val weight = 1.0 / (2.0 * (dim + lambda))
            for (i in 1 until numSigmaPoints) {
                wm[i] = weight
                wc[i] = weight
            }
        }

        /**
         * Generates sigma points array [numSigmaPoints x dim] from state mean and covariance.
         */
        fun generateSigmaPoints(mean: DoubleArray, cov: DenseMatrix64F): Array<DoubleArray> {
            val l = choleskyLower(cov)
            val sigma = Array(numSigmaPoints) { DoubleArray(dim) }

            // sigma_0 = mean
            System.arraycopy(mean, 0, sigma[0], 0, dim)

            // sigma_i = mean + gamma * L_i
            for (i in 0 until dim) {
                val ptPlus = DoubleArray(dim)
                val ptMinus = DoubleArray(dim)
                for (j in 0 until dim) {
                    val step = gamma * l.get(j, i)
                    ptPlus[j] = mean[j] + step
                    ptMinus[j] = mean[j] - step
                }
                sigma[i + 1] = ptPlus
                sigma[i + 1 + dim] = ptMinus
            }
            return sigma
        }

        /**
         * Reconstructs mean from sigma points.
         * If [angleIndex] is non-negative, circular statistics are used for that dimension.
         */
        fun recoverMean(sigmaPoints: Array<DoubleArray>, angleIndex: Int = -1): DoubleArray {
            val out = DoubleArray(dim)
            var sinSum = 0.0
            var cosSum = 0.0

            for (s in 0 until numSigmaPoints) {
                val pt = sigmaPoints[s]
                val w = wm[s]
                for (d in 0 until dim) {
                    if (d == angleIndex) {
                        sinSum += w * sin(pt[d])
                        cosSum += w * cos(pt[d])
                    } else {
                        out[d] += w * pt[d]
                    }
                }
            }
            if (angleIndex in 0 until dim) {
                out[angleIndex] = atan2(sinSum, cosSum)
            }
            return out
        }

        /**
         * Reconstructs covariance from sigma points and mean.
         */
        fun recoverCovariance(
            sigmaPoints: Array<DoubleArray>,
            mean: DoubleArray,
            angleIndex: Int = -1,
            noiseCov: DenseMatrix64F? = null
        ): DenseMatrix64F {
            val cov = DenseMatrix64F(dim, dim)
            for (s in 0 until numSigmaPoints) {
                val pt = sigmaPoints[s]
                val w = wc[s]
                val diff = DoubleArray(dim)
                for (d in 0 until dim) {
                    if (d == angleIndex) {
                        diff[d] = shortestAngleDelta(mean[d], pt[d])
                    } else {
                        diff[d] = pt[d] - mean[d]
                    }
                }
                for (i in 0 until dim) {
                    for (j in 0 until dim) {
                        cov.set(i, j, cov.get(i, j) + w * diff[i] * diff[j])
                    }
                }
            }
            if (noiseCov != null) {
                CommonOps.add(cov, noiseCov, cov)
            }
            return cov
        }
    }
}
