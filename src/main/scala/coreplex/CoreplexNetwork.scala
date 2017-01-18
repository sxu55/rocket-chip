// See LICENSE.SiFive for license details.

package coreplex

import Chisel._
import config._
import diplomacy._
import uncore.tilelink2._
import uncore.util._
import util._

trait CoreplexNetwork extends HasCoreplexParameters {
  val module: CoreplexNetworkModule

  val l1tol2 = LazyModule(new TLXbar)
  val l1tol2_beatBytes = l1tol2Config.beatBytes
  val l1tol2_lineBytes = p(CacheBlockBytes)

  val cbus = LazyModule(new TLXbar)
  val cbus_beatBytes = cbusConfig.beatBytes
  val cbus_lineBytes = l1tol2_lineBytes

  val intBar = LazyModule(new IntXbar)

  val mmio = TLOutputNode()
  val mmioInt = IntInputNode()

  intBar.intnode := mmioInt

  cbus.node :=
    TLBuffer()(
    TLAtomicAutomata(arithmetic = true)( // disable once TLB uses TL2 metadata
    TLWidthWidget(l1tol2_beatBytes)(
    l1tol2.node)))

  mmio :=
    TLBuffer()(
    TLWidthWidget(l1tol2_beatBytes)(
    l1tol2.node))
}

trait CoreplexNetworkBundle extends HasCoreplexParameters {
  val outer: CoreplexNetwork

  val mmio = outer.mmio.bundleOut
  val interrupts = outer.mmioInt.bundleIn
}

trait CoreplexNetworkModule extends HasCoreplexParameters {
  val outer: CoreplexNetwork
  val io: CoreplexNetworkBundle

  println("\nGenerated Address Map")
  for (manager <- outer.l1tol2.node.edgesIn(0).manager.managers) {
    val prot = (if (manager.supportsGet)     "R" else "") +
               (if (manager.supportsPutFull) "W" else "") +
               (if (manager.executable)      "X" else "") +
               (if (manager.supportsAcquireB) " [C]" else "")
    manager.address.foreach { a =>
      println(f"\t${manager.name}%s ${a.base}%x - ${a.base+a.mask+1}%x, $prot")
    }
  }
}

/////

trait BankedL2CoherenceManagers extends CoreplexNetwork {
  val module: BankedL2CoherenceManagersModule

  require (isPow2(l2Config.nBanksPerChannel))
  require (isPow2(l1tol2_lineBytes))

  val mem = Seq.fill(l2Config.nMemoryChannels) {
    val bankBar = LazyModule(new TLXbar)
    val output = TLOutputNode()

    output := bankBar.node
    val mask = ~BigInt((l2Config.nBanksPerChannel-1) * l1tol2_lineBytes)
    for (i <- 0 until l2Config.nBanksPerChannel) {
      val (in, out) = l2Config.coherenceManager(p)
      in := TLFilter(AddressSet(i * l1tol2_lineBytes, mask))(l1tol2.node)
      bankBar.node := out
    }

    output
  }
}

trait BankedL2CoherenceManagersBundle extends CoreplexNetworkBundle {
  val outer: BankedL2CoherenceManagers

  require (l2Config.nMemoryChannels <= 1, "Seq in Chisel Bundle needed to support > 1") // !!!
  val mem = outer.mem.map(_.bundleOut).toList.headOption // .headOption should be removed !!!
}

trait BankedL2CoherenceManagersModule extends CoreplexNetworkModule {
  val outer: BankedL2CoherenceManagers
  val io: BankedL2CoherenceManagersBundle
}

/////

trait HasL2MasterPort extends CoreplexNetwork {
  val module: HasL2MasterPortModule
  val l2in = TLInputNode()
  l1tol2.node := l2in
}

trait HasL2MasterPortBundle extends CoreplexNetworkBundle {
  val outer: HasL2MasterPort
  val l2in = outer.l2in.bundleIn
}

trait HasL2MasterPortModule extends CoreplexNetworkModule {
  val outer: HasL2MasterPort
  val io: HasL2MasterPortBundle
}
