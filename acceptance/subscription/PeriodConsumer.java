            import com.google.gson.*;
            public class PeriodConsumer {
              public static JsonObject consume(JsonObject subscription, String binding) {
                try {
                  if (binding == null || binding.isBlank() || !subscription.get("status").getAsString().equals("active")) throw new IllegalArgumentException();
                  var items = subscription.getAsJsonObject("items");
                  if (items.get("has_more").getAsBoolean()) throw new IllegalArgumentException();
                  JsonObject selected = null;
                  for (var item : items.getAsJsonArray("data")) {
                    var row = item.getAsJsonObject();
                    if (binding.equals(row.get("id").getAsString())) {
                      if (selected != null) throw new IllegalArgumentException();
                      selected = row;
                    }
                  }
                  if (selected == null) throw new IllegalArgumentException();
                  long end = selected.get("current_period_end").getAsLong();
                  if (end <= 0) throw new IllegalArgumentException();
                  var value = new JsonObject(); value.addProperty("status", "RESOLVED"); value.addProperty("periodEnd", end); return value;
                } catch (RuntimeException e) {
                  var value = new JsonObject(); value.addProperty("status", "DEFERRED"); return value;
                }
              }
            }
