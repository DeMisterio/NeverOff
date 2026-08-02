import io.github.jan.supabase.storage.storage
import io.github.jan.supabase.storage.Bucket
import kotlin.reflect.full.memberFunctions

fun main() {
    val clazz = Bucket::class
    clazz.memberFunctions.forEach { println(it.name) }
}
