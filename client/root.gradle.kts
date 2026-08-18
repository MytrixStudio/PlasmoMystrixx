plugins {
    id("gg.essential.multi-version.root")
}

group = "$group.client-root"

preprocess {
    strictExtraMappings.set(false)

    val fabric11904 = createNode("1.19.4-fabric", 11904, "official")
    val forge11904 = createNode("1.19.4-forge", 11904, "official")
    val forge12001 = createNode("1.20.1-forge", 12001, "official")
    val forge12004 = createNode("1.20.4-forge", 12004, "official")
    val forge12101 = createNode("1.21.1-forge", 12101, "official")
    val fabric12101 = createNode("1.21.1-fabric", 12101, "official")

    fabric11904.link(forge11904)
    forge11904.link(forge12001)
    forge12004.link(forge12001, file("1.20.4-1.20.1.txt"))
    forge12101.link(forge12004, file("1.21-1.20.6.txt"))
    forge12101.link(fabric12101)
}
