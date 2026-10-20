package craftq3

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency

/**
 * The allowed dependency edges between rewrite projects. A project may depend on anything in the
 * transitive closure of its listed edges; anything else fails the build at configuration time.
 * Legacy projects (":craftq3-*") are the behavioural oracle and may only be reached from
 * ":tools:legacy-oracle".
 */
final class ModuleGraph {
    static final Map<String, List<String>> EDGES = [
        ':engine:common'       : [],
        ':engine:fs'           : [':engine:common'],
        ':engine:formats'      : [':engine:fs'],
        ':engine:console'      : [':engine:common'],
        ':engine:net'          : [':engine:common'],
        ':engine:vm'           : [':engine:common'],
        ':engine:vm-abi'       : [':engine:vm', ':engine:console', ':engine:fs'],
        ':engine:collision'    : [':engine:formats'],
        ':engine:botlib'       : [':engine:collision'],
        ':engine:game'         : [':engine:vm-abi', ':engine:botlib', ':engine:net'],
        ':engine:render-ir'    : [':engine:formats'],
        ':engine:audio'        : [':engine:formats'],
        ':engine:client'       : [':engine:vm-abi', ':engine:net', ':engine:collision',
                                  ':engine:render-ir', ':engine:audio'],
        ':engine:session'      : [':engine:client', ':engine:game'],
        ':engine:headless'     : [':engine:session'],
        ':engine:testkit'      : [':engine:formats', ':engine:vm'],
        ':mc:hybrid'           : [':engine:game'],
        ':mc:fabric'           : [':engine:session', ':mc:hybrid'],
        ':tools:trace-format'  : [],
        ':tools:legacy-oracle' : [':tools:trace-format'],
        ':tools:arch-check'    : [],
    ]

    /** Test-only edges every rewrite project may use. */
    static final List<String> TEST_EDGES = [':engine:testkit', ':tools:trace-format']

    static boolean isRewrite(Project p) {
        p.path.startsWith(':engine:') || p.path.startsWith(':mc:') || p.path.startsWith(':tools:')
    }

    static Set<String> closure(String path) {
        Set<String> seen = new LinkedHashSet<>()
        Deque<String> work = new ArrayDeque<>(EDGES.getOrDefault(path, []))
        while (!work.isEmpty()) {
            String next = work.pop()
            if (seen.add(next)) work.addAll(EDGES.getOrDefault(next, []))
        }
        seen
    }

    static void verify(Project root) {
        List<String> violations = []
        root.subprojects.findAll { isRewrite(it) && it.childProjects.isEmpty() }.each { Project p ->
            if (!EDGES.containsKey(p.path)) {
                violations << "${p.path} is not declared in ModuleGraph.EDGES"
                return
            }
            Set<String> allowed = closure(p.path)
            p.configurations.each { conf ->
                boolean testConf = conf.name.toLowerCase().contains('test') || conf.name.toLowerCase().contains('jmh')
                conf.dependencies.withType(ProjectDependency).each { ProjectDependency dep ->
                    String target = dep.path
                    if (target == p.path) return
                    if (allowed.contains(target)) return
                    if (testConf && (TEST_EDGES.contains(target) || target == ':engine:testkit')) return
                    if (p.path == ':tools:legacy-oracle' && target.startsWith(':craftq3-')) return
                    if (p.path == ':tools:arch-check' && testConf) return
                    violations << "${p.path} (${conf.name}) -> ${target}"
                }
            }
        }
        if (!violations.isEmpty()) {
            throw new GradleException("Module graph violations (see build-logic ModuleGraph):\n  " + violations.unique().join('\n  '))
        }
    }
}
