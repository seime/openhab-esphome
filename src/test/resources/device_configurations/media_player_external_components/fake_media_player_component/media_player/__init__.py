import esphome.codegen as cg
from esphome.components import media_player
import esphome.config_validation as cv
from esphome.types import ConfigType

fake_media_player_component_ns = cg.esphome_ns.namespace("fake_media_player_component")
FakeMediaPlayer = fake_media_player_component_ns.class_(
    "FakeMediaPlayer", media_player.MediaPlayer, cg.Component
)

CONFIG_SCHEMA = media_player.media_player_schema(FakeMediaPlayer).extend(
    cv.COMPONENT_SCHEMA
)


async def to_code(config: ConfigType) -> None:
    var = await media_player.new_media_player(config)
    await cg.register_component(var, config)
